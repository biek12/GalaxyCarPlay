package com.galaxy.autorecord.storage

import com.galaxy.autorecord.core.ChannelRecord
import com.galaxy.autorecord.core.SegmentLayout
import com.galaxy.autorecord.core.SegmentManifest
import java.io.File
import java.util.LinkedHashMap
import java.util.UUID

/**
 * 单个录像片段的写入事务。
 *
 * 职责（WB-STORAGE-01）：建段目录（原子独占）→ 发布 writing 清单 → 接收真实写入器逐路
 * EOS/drain/muxer stop/release 结果 → 校验真实帧数/PTS/文件非空 → 经 [SegmentStorePort]
 * 完成 fsync/原子改名 → **全部视频处理完**才原子发布最终清单。
 *
 * 本批不接入录制引擎：不创建编码器、不打开相机、不写车辆/事件数据；[SegmentTransaction]
 * 只是"清单 + 提交"的事务外壳，真实编码器放 [ChannelWriteResult] 里回填，禁止仅传 Boolean 伪造。
 *
 * 纪律（编码与存储技术口径 §3/§4/§6；协作规范）：
 * - 始终按 [SegmentLayout.DIRECTIONS] 四向组装清单；未选/未提交/失败分别标 MISSING。
 *   只有四路都成功才 complete；有成功但不完整为 partial；零成功 failed。结果与
 *   [SegmentManifest.isComplete] 一致，绝不出现 state=complete 而 isComplete=false 的冲突。
 * - 空文件/坏文件/单路失败不得标 complete；不先删旧文件；不编造元数据。
 * - finish/abort/close 幂等；单一写线程所有权；构造/调用不创建无界后台线程。
 * - 文件/清单提交失败保留已有证据并返回具名错误，不抛成功；端口异常转具名失败并固化终态，
 *   不把半提交事务留在可重复写状态。
 *
 * @param rootDir 选定存储根目录（如 App 专属 files 目录）。段目录由 SegmentLayout 派生，**必须恒在其内且为绝对路径**。
 * @param volumeUuid 卷标识，null＝内置存储；仅原样记录进清单。
 * @param startedAtWallMillis 墙钟锚点（毫秒），必须 > 0。
 * @param startedAtMonotonicNanos 单调时钟锚点（纳秒），必须 >= 0；回放/事件对齐只用单调时钟。
 * @param segmentIndex 段号（同一次会话内的序号），>= 0。
 * @param selectedDirections 本段实际录制的方向子集（必须是 SegmentLayout.DIRECTIONS 的子集）。
 * @param appVersionName 应用版本名，写入清单供事后复查，必须非空。
 * @param store 磁盘端口；真实环境传 [RealSegmentStorePort]，测试传假实现。
 */
class SegmentTransaction(
    private val rootDir: File,
    private val volumeUuid: String?,
    private val startedAtWallMillis: Long,
    private val startedAtMonotonicNanos: Long,
    private val segmentIndex: Int,
    selectedDirections: Collection<String>,
    private val appVersionName: String,
    private val store: SegmentStorePort,
) {
    /** 本事务身份；真实写入器回填的 [ChannelWriteResult.transactionId] 必须与之匹配。 */
    val transactionId: String = UUID.randomUUID().toString()

    /** 选定方向（只读副本，构造后不可变）。 */
    private val directions: Set<String> = selectedDirections.toSet()

    /** 段目录，由 SegmentLayout 从 rootDir 派生，恒在 rootDir 内。 */
    private val segmentDir: File = SegmentLayout.segmentDir(rootDir, startedAtWallMillis, segmentIndex)

    /** 规范化绝对根目录（构造期校验后固定）。 */
    private val safeRoot: File
    private val safeSegment: File

    private enum class Phase { PENDING, WRITING, TERMINAL, FAILED }

    @Volatile private var phase = Phase.PENDING
    @Volatile private var ownerThread: Thread? = null
    private var transferred=false

    /** 每路提交结果（方向 → 结果）。提交前为空。保持插入顺序便于清单稳定。 */
    private val channelResults = LinkedHashMap<String, ChannelCommitOutcome>()
    private val channelInputs = LinkedHashMap<String, ChannelWriteResult>()

    private var lastFinishOutcome: FinishOutcome? = null

    /** 侧车记录（车辆样本/事件）；由真实录制引擎在 finish() 前挂载，随最终清单一起发布。 */
    private var vehicleRecord: com.galaxy.autorecord.core.VehicleRecord? = null
    private var eventsRecord: com.galaxy.autorecord.core.EventsRecord? = null

    init {
        // R1-5：先检查输入是否为绝对路径（canonical 之后一定绝对，故必须在解析前查）。
        require(rootDir.isAbsolute) { "存储根目录必须是绝对路径：相对路径不可用作存储根" }
        require(directions.isNotEmpty()) { "未选择任何录制方向" }
        for (d in directions) {
            require(SegmentLayout.isValidDirection(d)) {
                "非法录制方向：$d（允许：${SegmentLayout.DIRECTIONS}）"
            }
        }
        require(startedAtWallMillis > 0) { "墙钟锚点必须为正（毫秒）" }
        require(startedAtMonotonicNanos >= 0) { "单调时钟锚点不能为负（纳秒）" }
        require(segmentIndex >= 0) { "段号必须 >= 0" }
        // 不安全路径：原始输入含 ".." 即拒绝（canonical 之后已解析，必须在解析前查）。
        require(!rootDir.path.split(File.separator).contains("..")) { "不安全的存储根路径（含 ..）" }
        val safe = rootDir.canonicalFile
        require(safe.isAbsolute) { "存储根目录必须是绝对路径" }
        require(
            segmentDir.canonicalPath.startsWith(safe.canonicalPath + File.separator) ||
                segmentDir.canonicalPath == safe.canonicalPath,
        ) { "段目录越过选定根目录" }
        require(appVersionName.isNotBlank()) { "应用版本名不能为空" }
        safeRoot = safe
        safeSegment = segmentDir.canonicalFile
    }

    private fun checkOwner() {
        val owner = ownerThread ?: return
        check(Thread.currentThread() == owner) {
            "SegmentTransaction 只能由 begin() 所在的写线程操作（owner=${owner.name}, current=${Thread.currentThread().name}）"
        }
    }

    /** 全部方向跨过边界、旧段不再接包后，由当前所有者显式移交给唯一收尾线程。 */
    @Synchronized
    fun transferToFinalizer(next:Thread) {
        checkOwner()
        check(phase==Phase.WRITING){"only_writing_transaction_can_transfer"}
        check(!transferred&&ownerThread!==next){"transaction_already_transferred"}
        transferred=true;ownerThread=next
    }

    /**
     * 开始事务：原子独占创建段目录 → 发布 writing 清单。
     * 重复调用（非 PENDING）报错；创建或写清单失败置 [Phase.FAILED] 并抛错，事务不可复用。
     * 不依赖单独的 exists 检查（R1-2，消除 TOCTOU）。
     */
    @Synchronized
    fun begin(): SegmentDirInfo {
        check(phase == Phase.PENDING) { "begin() 只能调用一次（当前 phase=$phase）" }
        ownerThread = Thread.currentThread()
        val dirOk = try {
            checkLocation()
            store.ensureSegmentDir(segmentDir)
        } catch (e: Exception) {
            phase = Phase.FAILED
            throw IllegalStateException("创建段目录失败：${segmentDir.name}（${e.javaClass.simpleName}）")
        }
        if (!dirOk) {
            phase = Phase.FAILED
            throw IllegalStateException("段目录已存在或无法创建：${segmentDir.name}")
        }
        val manifestOk = try {
            checkLocation(File(segmentDir, SegmentLayout.FILE_MANIFEST))
            store.writeManifest(segmentDir, buildWritingManifest())
        } catch (e: Exception) {
            phase = Phase.FAILED
            throw IllegalStateException("发布 writing 清单失败：${segmentDir.name}（${e.javaClass.simpleName}）")
        }
        if (!manifestOk) {
            // 目录已建好但清单失败：保留证据，固化 FAILED，禁止复用或写入别人的目录。
            phase = Phase.FAILED
            throw IllegalStateException("发布 writing 清单失败：${segmentDir.name}")
        }
        phase = Phase.WRITING
        return SegmentDirInfo(segmentDir, directions.toList())
    }

    /**
     * 提交一路真实写入器的收尾结果。
     *
     * 顺序：事务身份校验 → 幂等（合法重复）→ 元数据合法性 → 写入目标在根内 → 写入文件非空（区分 I/O 错误）→
     * [SegmentStorePort.commitVideo]（fsync+改名）。任一环节失败都把该路记 failed 并保留
     * `.writing/.partial` 证据；不删文件、不编造 complete。
     */
    fun commitChannel(result: ChannelWriteResult): ChannelCommitOutcome {
        checkOwner()
        check(phase == Phase.WRITING) { "必须先 begin() 成功进入 WRITING 才能提交通道（当前 phase=$phase）" }
        val dir = result.direction
        require(dir in directions) { "方向 $dir 不在本段选定方向 $directions 内" }

        // 先验身份，再看缓存；迟到旧结果只能被拒绝，不能污染本次方向的结果。
        if (result.transactionId != transactionId) {
            return ChannelCommitOutcome.failed(dir, "事务身份不匹配")
        }
        channelInputs[dir]?.let { original ->
            return if (original == result) channelResults.getValue(dir)
                else ChannelCommitOutcome.failed(dir, "重复提交内容冲突")
        }
        channelInputs[dir] = result

        // 1) 元数据合法性：不接受仅一个成功 Boolean 就编造的结果。
        val metaError = validateResult(result)
        if (metaError != null) {
            return recordFailed(dir, metaError)
        }

        val video = SegmentLayout.videoFile(segmentDir, dir)
        val writing = SegmentLayout.writingFile(video)
        // R1-5：写入目标必须仍在本事务段目录内（段目录已在构造期确保其位于根内）。
        try { checkLocation(video); checkLocation(writing) }
        catch (error: Exception) { return recordFailed(dir, "写入路径已变化：${error.javaClass.simpleName}") }

        // 2) 真实写入文件非空校验；I/O 错误与空文件必须区分（R1-5）。
        val len = try {
            store.fileLength(writing)
        } catch (e: Exception) {
            return recordFailed(dir, "读取写入文件长度失败（I/O 错误）")
        }
        if (len <= 0L) {
            return recordFailed(dir, "写入文件为空或缺失：${writing.name}（len=$len）")
        }

        // 3) 真实提交：fsync + 原子改名（端口委托 AtomicFiles）。
        val committed = try {
            store.commitVideo(video)
        } catch (e: Exception) {
            // 不打印异常 message / 绝对路径，避免泄露私有信息（R1-5）。
            return recordFailed(dir, "提交视频文件异常：${e.javaClass.simpleName}")
        }
        return if (committed) {
            val rec = ChannelCommitOutcome.succeeded(dir, toChannelRecord(dir, result))
            channelResults[dir] = rec
            rec
        } else {
            recordFailed(dir, "视频文件改名/fsync 失败（保留 .writing/.partial 证据）")
        }
    }

    /**
     * 挂载侧车记录（车辆样本 vehicles.jsonl / 事件 events.json 的清单条目）。
     * 只能在 WRITING 态调用；记录的是真实写入器产出的计数，不允许先于写入伪造。
     */
    @Synchronized
    fun attachSidecar(vehicle: com.galaxy.autorecord.core.VehicleRecord?, events: com.galaxy.autorecord.core.EventsRecord?) {
        checkOwner()
        check(phase == Phase.WRITING) { "attachSidecar() 仅允许在 begin() 之后、finish() 之前调用（当前 phase=$phase）" }
        vehicleRecord = vehicle
        eventsRecord = events
    }

    /**
     * 收尾：全部视频处理完才发布最终清单。幂等。
     * 端口抛异常时转为具名失败结果并固化终态，不抛给上层（R1-3）。
     */
    fun finish(): FinishOutcome {
        checkOwner()
        if (phase == Phase.TERMINAL) return lastFinishOutcome!!
        check(phase == Phase.WRITING) { "finish() 仅允许在 begin() 之后调用（当前 phase=$phase）" }
        val outcome = writeTerminationManifest()
        lastFinishOutcome = outcome
        phase = Phase.TERMINAL
        return outcome
    }

    /**
     * 中止：保留已提交证据，把未提交/未选/失败方向标 missing，已提交方向保留真实状态。幂等。
     * PENDING（未 begin）调用抛错；TERMINAL 直接返回上次结果；端口异常转具名失败并固化终态。
     */
    fun abort(reason: String = "主动中止"): FinishOutcome {
        checkOwner()
        if (phase == Phase.TERMINAL) return lastFinishOutcome!!
        if (phase == Phase.PENDING) {
            throw IllegalStateException("abort() 需先 begin()（当前 phase=PENDING）")
        }
        check(phase == Phase.WRITING) { "abort() 不可用于失败的建段事务（当前 phase=$phase）" }
        val outcome = writeTerminationManifest(errorPrefix = "abort $reason：")
        lastFinishOutcome = outcome
        phase = Phase.TERMINAL
        return outcome
    }

    /**
     * 释放/兜底：本事务不持有线程或流（真实写入器持有那些），故 close 只是幂等地确保已收尾。
     * WRITING 态未收尾等同 abort（保留证据）；PENDING（未 begin）为空操作，不创建任何文件（R1-3）。
     */
    fun close() {
        if (phase == Phase.WRITING) abort("close() 在未 finish 时调用")
    }

    // ---- 只读查询（便于测试与上层断言）----

    /** 段目录（由 SegmentLayout 派生）。 */
    fun segmentDirectory(): File = segmentDir

    /** 是否已终止（finish/abort 调用过，含异常固化终态后）。 */
    fun isTerminated(): Boolean = phase == Phase.TERMINAL

    /** 是否因 begin 失败而进入不可用终态（FAILED），只可读、不可复用或重复写入。 */
    fun isFailed(): Boolean = phase == Phase.FAILED

    /** 某方向提交结果；未提交返回 null。 */
    fun channelOutcome(direction: String): ChannelCommitOutcome? = channelResults[direction]

    // ---- 内部构造 ----

    /** 每次磁盘操作前重验路径，构造之后的父目录/输出符号链接变化同样必须拒绝。 */
    private fun checkLocation(output: File? = null) {
        check(rootDir.canonicalFile == safeRoot) { "存储根目录已变化" }
        check(segmentDir.canonicalFile == safeSegment && safeSegment.toPath().startsWith(safeRoot.toPath())) {
            "段目录已变化"
        }
        if (output != null) {
            check(output.parentFile == segmentDir && output.canonicalFile == File(safeSegment, output.name)) {
                "输出路径已变化"
            }
        }
    }

    /**
     * 计算并发布终止清单（writing 后由 finish/abort 共用）。端口异常时转为具名失败结果，
     * 不抛给上层；调用方负责把结果固化到 [lastFinishOutcome] 与终态。
     */
    private fun writeTerminationManifest(errorPrefix: String = ""): FinishOutcome {
        val succeededDirs = channelResults.filterValues { it.succeeded }.keys
        val channels = buildTerminationChannels()
        val state = computeTerminationState(succeededDirs.size)
        val durationMillis = computeDurationMillis()
        val manifest = assembleManifest(state, channels, durationMillis)
        var ok = false
        var error: String? = null
        try {
            checkLocation(File(segmentDir, SegmentLayout.FILE_MANIFEST))
            ok = store.writeManifest(segmentDir, manifest)
            error = if (ok) null else "${errorPrefix}清单写入失败（视频证据已落盘，未伪造 complete）"
        } catch (e: Exception) {
            ok = false
            error = "${errorPrefix}清单写入异常：${e.javaClass.simpleName}"
        }
        return FinishOutcome(
            segmentDir = segmentDir,
            state = state,
            manifestWritten = ok,
            completedDirections = succeededDirs.toList(),
            failedDirections = (SegmentLayout.DIRECTIONS - succeededDirs).toList(),
            error = error,
        )
    }

    private fun validateResult(r: ChannelWriteResult): String? {
        if (r.frameCount <= 0) return "帧数为 0 或负：${r.frameCount}"
        if (r.firstPtsUs < 0) return "首帧 PTS 为负：${r.firstPtsUs}"
        if (r.lastPtsUs < r.firstPtsUs || (r.frameCount > 1 && r.lastPtsUs == r.firstPtsUs)) {
            return "PTS 区间为空或非单调：last(${r.lastPtsUs}) <= first(${r.firstPtsUs})"
        }
        if (r.width <= 0 || r.height <= 0) return "非法分辨率：${r.width}x${r.height}"
        if (r.fps <= 0) return "帧率非正：${r.fps}"
        if (r.bitrateBps <= 0) return "码率非正：${r.bitrateBps}"
        if (r.codecMime.isBlank()) return "编码器 MIME 为空"
        if (r.encoderName.isBlank()) return "编码器名为空" // R1-5
        if (r.gopSeconds <= 0) return "GOP 非正：${r.gopSeconds}" // R1-5
        if (r.implementation !in VALID_IMPLEMENTATIONS) return "未知实现三态：${r.implementation}"
        return null
    }

    private fun recordFailed(direction: String, reason: String): ChannelCommitOutcome {
        val outcome = ChannelCommitOutcome.failed(direction, reason)
        channelResults[direction] = outcome
        return outcome
    }

    private fun toChannelRecord(dir: String, r: ChannelWriteResult): ChannelRecord = ChannelRecord(
        direction = dir,
        file = SegmentLayout.videoFile(segmentDir, dir).name,
        codecMime = r.codecMime,
        encoderName = r.encoderName,
        implementation = r.implementation,
        width = r.width,
        height = r.height,
        fps = r.fps,
        bitrateBps = r.bitrateBps,
        gopSeconds = r.gopSeconds,
        frameCount = r.frameCount,
        firstPtsUs = r.firstPtsUs,
        lastPtsUs = r.lastPtsUs,
        status = SegmentManifest.CHANNEL_COMPLETE,
    )

    private fun missingChannel(dir: String): ChannelRecord = ChannelRecord(
        direction = dir,
        file = SegmentLayout.videoFile(segmentDir, dir).name,
        codecMime = "",
        encoderName = "",
        implementation = "",
        width = 0,
        height = 0,
        fps = 0,
        bitrateBps = 0,
        gopSeconds = 0,
        frameCount = 0,
        firstPtsUs = 0,
        lastPtsUs = 0,
        status = SegmentManifest.CHANNEL_MISSING,
    )

    /** writing 清单：四向固定，选定方向标 writing，未选方向标 missing。 */
    private fun buildWritingManifest(): SegmentManifest {
        val channels = SegmentLayout.DIRECTIONS.map { dir ->
            if (dir in directions) {
                ChannelRecord(
                    direction = dir,
                    file = SegmentLayout.videoFile(segmentDir, dir).name,
                    codecMime = "",
                    encoderName = "",
                    implementation = "",
                    width = 0,
                    height = 0,
                    fps = 0,
                    bitrateBps = 0,
                    gopSeconds = 0,
                    frameCount = 0,
                    firstPtsUs = 0,
                    lastPtsUs = 0,
                    // 仅本事务的"写入中"标记；Scanner 见到非 complete 即不判完整。
                    status = CHANNEL_WRITING,
                )
            } else {
                missingChannel(dir)
            }
        }
        return assembleManifest(SegmentManifest.STATE_WRITING, channels, 0L)
    }

    /** 终止清单：四向固定，成功方向标 complete，其余（未选/未提交/失败）标 missing。 */
    private fun buildTerminationChannels(): List<ChannelRecord> {
        return SegmentLayout.DIRECTIONS.map { dir ->
            val outcome = channelResults[dir]
            if (outcome != null && outcome.succeeded) {
                outcome.channel!!
            } else {
                missingChannel(dir)
            }
        }
    }

    /**
     * 终止态判定（R1-1）：必须始终按四向口径。
     * 四路全成功 → complete；零成功 → failed；有成功但不完整 → partial。
     */
    private fun computeTerminationState(succeededCount: Int): String = when {
        succeededCount == SegmentLayout.DIRECTIONS.size -> SegmentManifest.STATE_COMPLETE
        succeededCount == 0 -> SegmentManifest.STATE_FAILED
        else -> SegmentManifest.STATE_PARTIAL
    }

    /**
     * 段相对时间轴时长 = 最晚末帧 PTS（段相对域，单位毫秒）。
     *
     * R2 裁决（Lead 边界反例"段相对PTS保留起始缺口而非减去各路首帧"为准）：
     * 文件内 PTS 为段相对时间轴（0 = 段锚点），首帧晚于段起点的缺口属于段内容的一部分，
     * 时长必须保留该缺口（= max(lastPtsUs)），回放时间轴才能覆盖到末帧、与 vehicles.jsonl
     * 的"相对片段起点毫秒偏移"同域对齐。禁止用 (末−首) 裁掉缺口，更禁止用绝对 uptime 充当时长。
     */
    private fun computeDurationMillis(): Long {
        val recs = SegmentLayout.DIRECTIONS.mapNotNull { dir ->
            channelResults[dir]?.takeIf { it.succeeded }?.channel
        }
        if (recs.isEmpty()) return 0L
        return recs.maxOf { it.lastPtsUs } / 1000
    }

    private fun assembleManifest(
        state: String,
        channels: List<ChannelRecord>,
        durationMillis: Long,
    ): SegmentManifest = SegmentManifest(
        schemaVersion = SegmentLayout.SCHEMA_VERSION,
        segmentId = segmentDir.name,
        startedAtWallMillis = startedAtWallMillis,
        startedAtMonotonicNanos = startedAtMonotonicNanos,
        durationMillis = durationMillis,
        state = state,
        storageVolumeUuid = volumeUuid,
        channels = channels,
        vehicle = vehicleRecord,
        events = eventsRecord,
        appVersionName = appVersionName,
    )

    companion object {
        /** 本事务内部"写入中"通道状态标记（区别于 SegmentManifest 三态）。 */
        const val CHANNEL_WRITING = "writing"

        /** 允许的编码器实现三态（与 RecordSettings.IMPL_*、"unknown" 对齐）。 */
        private val VALID_IMPLEMENTATIONS = setOf("hardware", "software", "unknown")
    }
}
