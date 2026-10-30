package com.ecarx.autoservice.vehicle

/** 只读语义映射；完整原始目录另由平台枚举。ID/换算来自FS12/FX12事实，不复制厂商SDK实现。 */
internal data class SignalSpec(val key: String, val propertyId: Int, val unit: String,
    val divisor: Double = 1.0, val minimum: Double = -Double.MAX_VALUE,
    val maximum: Double = Double.MAX_VALUE, val gear: Boolean = false,
    val normalizedId: Int = 0, val name: String = key, val mapping: Map<String, String> = emptyMap())

internal object VehicleCatalog {
    private val l6Specs by lazy { create("L6") }
    private val l7Specs by lazy { create("L7") }
    fun forModel(model: String): List<SignalSpec> = when (model) {
        "L6" -> l6Specs
        "L7" -> l7Specs
        else -> throw IllegalArgumentException("unsupported_model")
    }
    private fun create(model: String): List<SignalSpec> {
        require(model in setOf("L6", "L7"))
        val l6 = model == "L6"
        return listOf(
            SignalSpec("displaySpeed", if (l6) 557874774 else 557874774, "km/h", 1.0, normalizedId = 10001, name = "SIVehSpdIndcd", mapping = emptyMap()),
            SignalSpec("soc", if (l6) 561024567 else 561024570, "%", 1.0, normalizedId = 10002, name = "paegyhvbattsoc", mapping = emptyMap()),
            SignalSpec("gear", if (l6) 557874335 else 557874335, "raw", 1.0, normalizedId = 10003, name = "GearLvrIndcn", mapping = mapOf("0" to "P", "1" to "R", "2" to "N", "3" to "D")),
            SignalSpec("property.10004", if (l6) 557874371 else 557874371, "raw", 1.0, normalizedId = 10004, name = "PtGearAct", mapping = emptyMap()),
            SignalSpec("outsideTemperature", if (l6) 559970553 else 559970553, "°C", 1.0, normalizedId = 10005, name = "AmbTRawAmbTVal", mapping = emptyMap()),
            SignalSpec("insideTemperature", if (l6) 559970555 else 559970555, "°C", 1.0, normalizedId = 10006, name = "CmptmtTFrntCmptmtTFrnt", mapping = emptyMap()),
            SignalSpec("property.10007", if (l6) 561024222 else 561024224, "raw", 1.0, normalizedId = 10007, name = "pacenlocksetting", mapping = emptyMap()),
            SignalSpec("brakeLight", if (l6) 557873489 else 557873489, "raw", 1.0, normalizedId = 10008, name = "ExtrLtgStsStopLi", mapping = emptyMap()),
            SignalSpec("leftTurn", if (l6) 557873490 else 557873490, "raw", 1.0, normalizedId = 10009, name = "ExtrLtgStsTurnIndrLe", mapping = emptyMap()),
            SignalSpec("rightTurn", if (l6) 557873491 else 557873491, "raw", 1.0, normalizedId = 10010, name = "ExtrLtgStsTurnIndrRi", mapping = emptyMap()),
            SignalSpec("fuel", if (l6) 561024446 else 561024448, "%", 1.0, normalizedId = 10011, name = "pafuelremainperc", mapping = emptyMap()),
            SignalSpec("property.10012", if (l6) 561025089 else 561025093, "L", 1000.0, normalizedId = 10012, name = "pafulvlindcdfulvlvalfromfutbl", mapping = emptyMap()),
            SignalSpec("tire.frontLeft", if (l6) 561024429 else 561024431, "kPa", 10.0, normalizedId = 10013, name = "patirepressurefrontleft", mapping = emptyMap()),
            SignalSpec("tire.frontRight", if (l6) 561024430 else 561024432, "kPa", 10.0, normalizedId = 10014, name = "patirepressurefrontright", mapping = emptyMap()),
            SignalSpec("tire.rearLeft", if (l6) 561024431 else 561024433, "kPa", 10.0, normalizedId = 10015, name = "patirepressurerearleft", mapping = emptyMap()),
            SignalSpec("tire.rearRight", if (l6) 561024432 else 561024434, "kPa", 10.0, normalizedId = 10016, name = "patirepressurerearright", mapping = emptyMap()),
            SignalSpec("property.10017", if (l6) 557873270 else 557873270, "raw", 1.0, normalizedId = 10017, name = "DoorDrvrSts", mapping = mapOf("2" to "0")),
            SignalSpec("property.10018", if (l6) 557873277 else 557873277, "raw", 1.0, normalizedId = 10018, name = "DoorPassSts", mapping = mapOf("2" to "0")),
            SignalSpec("property.10019", if (l6) 557873271 else 557873271, "raw", 1.0, normalizedId = 10019, name = "DoorLeReSts", mapping = mapOf("2" to "0")),
            SignalSpec("property.10020", if (l6) 557873278 else 557873278, "raw", 1.0, normalizedId = 10020, name = "DoorRiReSts", mapping = mapOf("2" to "0")),
            SignalSpec("rpm", if (l6) 557850019 else 557850019, "rpm", 2.0, normalizedId = 10021, name = "CLUSTER_POWERFLOW_ENGSPDDISPD", mapping = emptyMap()),
            SignalSpec("fuelRange", if (l6) 561024546 else 561024549, "km", 1.0, normalizedId = 10022, name = "patsdteindicated", mapping = emptyMap()),
            SignalSpec("property.10023", if (l6) 557871401 else 557871401, "raw", 1.0, normalizedId = 10023, name = "AsyLineLeColor", mapping = emptyMap()),
            SignalSpec("property.10024", if (l6) 557871402 else 557871402, "raw", 1.0, normalizedId = 10024, name = "AsyLineLeTyp", mapping = emptyMap()),
            SignalSpec("property.10025", if (l6) 557871403 else 557871403, "raw", 1.0, normalizedId = 10025, name = "AsyLineRiColor", mapping = emptyMap()),
            SignalSpec("property.10026", if (l6) 557871404 else 557871404, "raw", 1.0, normalizedId = 10026, name = "AsyLineRiTyp", mapping = emptyMap()),
            SignalSpec("property.10027", if (l6) 557849113 else 557849113, "raw", 1.0, normalizedId = 10027, name = "CLUSTER_CHIME_LCMAINDCNLE", mapping = emptyMap()),
            SignalSpec("property.10028", if (l6) 557849114 else 557849114, "raw", 1.0, normalizedId = 10028, name = "CLUSTER_CHIME_LCMAINDCNRI", mapping = emptyMap()),
            SignalSpec("property.10029", if (l6) 557849115 else 557849115, "raw", 1.0, normalizedId = 10029, name = "CLUSTER_CHIME_LETTCLV", mapping = emptyMap()),
            SignalSpec("property.10030", if (l6) 557849116 else 557849116, "raw", 1.0, normalizedId = 10030, name = "CLUSTER_CHIME_RITTCLV", mapping = emptyMap()),
            SignalSpec("property.10031", if (l6) 557848149 else 557848149, "raw", 1.0, normalizedId = 10031, name = "APA_OUTDSNSRDSTOFFRNTLEBLK1", mapping = emptyMap()),
            SignalSpec("property.10032", if (l6) 557848154 else 557848154, "raw", 1.0, normalizedId = 10032, name = "APA_OUTDSNSRDSTOFFRNTRIBLK1", mapping = emptyMap()),
            SignalSpec("property.10033", if (l6) 557848141 else 557848141, "raw", 1.0, normalizedId = 10033, name = "APA_OUTDSNSRDSTOFRELEBLK1", mapping = emptyMap()),
            SignalSpec("property.10034", if (l6) 557848143 else 557848143, "raw", 1.0, normalizedId = 10034, name = "APA_INSDSNSRDSTOFRELEBLK1", mapping = emptyMap()),
            SignalSpec("property.10035", if (l6) 557848145 else 557848145, "raw", 1.0, normalizedId = 10035, name = "APA_INSDSNSRDSTOFRERIBLK1", mapping = emptyMap()),
            SignalSpec("property.10036", if (l6) 557848147 else 557848147, "raw", 1.0, normalizedId = 10036, name = "APA_OUTDSNSRDSTOFRERIBLK1", mapping = emptyMap()),
            SignalSpec("property.10037", if (l6) 289475088 else 289475088, "raw", 1.0, normalizedId = 10037, name = "HW_KEY_INPUT", mapping = emptyMap()),
            SignalSpec("lowBeam", if (l6) 557849510 else 557849510, "raw", 1.0, normalizedId = 10038, name = "CLUSTER_WARNING_EXTRLTGSTSLOBEAM", mapping = emptyMap()),
            SignalSpec("highBeam", if (l6) 557849181 else 557849181, "raw", 1.0, normalizedId = 10039, name = "CLUSTER_WARNING_EXTRLTGSTSHIBEAM", mapping = emptyMap()),
            SignalSpec("property.10040", if (l6) 557850632 else 557850632, "raw", 1.0, normalizedId = 10040, name = "LCM_EPBLAMPREQEPBLAMPREQ", mapping = mapOf("0" to "1", "1" to "0")),
            SignalSpec("property.10041", if (l6) 557849179 else 557849179, "raw", 1.0, normalizedId = 10041, name = "CLUSTER_WARNING_EXTRLTGSTSPOSLIFRNT", mapping = emptyMap()),
            SignalSpec("property.10042", if (l6) 557855125 else 557855125, "raw", 1.0, normalizedId = 10042, name = "HU_EXTRLTGSTSREFOG", mapping = emptyMap()),
            SignalSpec("property.10043", if (l6) 557969412 else 557969412, "raw", 1.0, normalizedId = 10043, name = "AP_POWER_MODE_REQUEST", mapping = emptyMap()),
            SignalSpec("property.10044", if (l6) 557848166 else 557848166, "raw", 1.0, normalizedId = 10044, name = "APA_INDCRSTS", mapping = emptyMap()),
            SignalSpec("property.10045", if (l6) 557854741 else 557854741, "raw", 1.0, normalizedId = 10045, name = "HU_MIRRFOLDSTSATDRVR", mapping = mapOf("1" to "0", "4" to "1")),
            SignalSpec("property.10046", if (l6) 557854742 else 557854742, "raw", 1.0, normalizedId = 10046, name = "HU_MIRRFOLDSTSATPASS", mapping = mapOf("1" to "0", "4" to "1")),
            SignalSpec("property.10047", if (l6) 557855068 else 557855068, "raw", 1.0, normalizedId = 10047, name = "HU_PASSSEATSTS", mapping = mapOf("2" to "1")),
            SignalSpec("property.30011", if (l6) 557850628 else 557850628, "raw", 1.0, normalizedId = 30011, name = "LCM_TRSTS", mapping = mapOf("2" to "0")),
            SignalSpec("property.10049", if (l6) 557849127 else 557849127, "raw", 1.0, normalizedId = 10049, name = "CLUSTER_CHIME_SPDLIMFIRST", mapping = emptyMap()),
            SignalSpec("property.30010", if (l6) 561023787 else 561023789, "raw", 1.0, normalizedId = 30010, name = "panotifdimshowhide", mapping = emptyMap()),
            SignalSpec("property.30001", if (l6) 557871549 else 557871549, "raw", 1.0, normalizedId = 30001, name = "FaceIdnResForProfYesNo", mapping = emptyMap()),
            SignalSpec("property.30002", if (l6) 557873390 else 557873390, "raw", 1.0, normalizedId = 30002, name = "WinPosnStsAtDrvr", mapping = mapOf("1" to "1", "2" to "1", "3" to "2", "4" to "2", "5" to "2", "6" to "3", "7" to "3", "8" to "4", "9" to "4", "10" to "4", "11" to "5", "12" to "5", "13" to "6", "14" to "6", "15" to "6", "16" to "7", "17" to "7", "18" to "8", "19" to "8", "20" to "8", "21" to "9", "22" to "9", "23" to "9", "24" to "10", "25" to "10", "26" to "10")),
            SignalSpec("property.30003", if (l6) 557873391 else 557873391, "raw", 1.0, normalizedId = 30003, name = "WinPosnStsAtPass", mapping = mapOf("1" to "1", "2" to "1", "3" to "2", "4" to "2", "5" to "2", "6" to "3", "7" to "3", "8" to "4", "9" to "4", "10" to "4", "11" to "5", "12" to "5", "13" to "6", "14" to "6", "15" to "6", "16" to "7", "17" to "7", "18" to "8", "19" to "8", "20" to "8", "21" to "9", "22" to "9", "23" to "9", "24" to "10", "25" to "10", "26" to "10")),
            SignalSpec("property.30004", if (l6) 557873392 else 557873392, "raw", 1.0, normalizedId = 30004, name = "WinPosnStsAtReLe", mapping = mapOf("1" to "1", "2" to "1", "3" to "2", "4" to "2", "5" to "2", "6" to "3", "7" to "3", "8" to "4", "9" to "4", "10" to "4", "11" to "5", "12" to "5", "13" to "6", "14" to "6", "15" to "6", "16" to "7", "17" to "7", "18" to "8", "19" to "8", "20" to "8", "21" to "9", "22" to "9", "23" to "9", "24" to "10", "25" to "10", "26" to "10")),
            SignalSpec("property.30005", if (l6) 557873393 else 557873393, "raw", 1.0, normalizedId = 30005, name = "WinPosnStsAtReRi", mapping = mapOf("1" to "1", "2" to "1", "3" to "2", "4" to "2", "5" to "2", "6" to "3", "7" to "3", "8" to "4", "9" to "4", "10" to "4", "11" to "5", "12" to "5", "13" to "6", "14" to "6", "15" to "6", "16" to "7", "17" to "7", "18" to "8", "19" to "8", "20" to "8", "21" to "9", "22" to "9", "23" to "9", "24" to "10", "25" to "10", "26" to "10")),
            SignalSpec("property.30006", if (l6) 557854783 else 557854783, "raw", 1.0, normalizedId = 30006, name = "HU_READLISTSFIRSTROWLE", mapping = mapOf("2" to "0")),
            SignalSpec("property.30007", if (l6) 557854784 else 557854784, "raw", 1.0, normalizedId = 30007, name = "HU_READLISTSFIRSTROWRI", mapping = mapOf("2" to "0")),
            SignalSpec("property.30008", if (l6) 557854785 else 557854785, "raw", 1.0, normalizedId = 30008, name = "HU_READLISTSSECONDROWLE", mapping = mapOf("2" to "0")),
            SignalSpec("property.30009", if (l6) 557854786 else 557854786, "raw", 1.0, normalizedId = 30009, name = "HU_READLISTSSECONDROWRI", mapping = mapOf("2" to "0")),
            SignalSpec("property.10050", if (l6) 561024653 else 561024656, "raw", 1.0, normalizedId = 10050, name = "paegychrgncableconn", mapping = emptyMap()),
            SignalSpec("property.10051", if (l6) 561024572 else 561024575, "raw", 1.0, normalizedId = 10051, name = "pavehchargchargacst", mapping = emptyMap()),
            SignalSpec("property.10052", if (l6) 561024475 else 561024477, "raw", 1.0, normalizedId = 10052, name = "pavstdmodests", mapping = emptyMap()),
            SignalSpec("property.10053", if (l6) 557850644 else 557850644, "raw", 1.0, normalizedId = 10053, name = "LCM_ALRMSTSALRMST", mapping = emptyMap()),
            SignalSpec("accelerator", if (l6) 559947208 else 559947208, "%", 1.0, normalizedId = 10054, name = "CLUSTER_VEHMODMNGTGLBSAFE1ACCRPEDLRAT", mapping = emptyMap()),
            SignalSpec("brake", if (l6) 557855126 else 557855126, "%", 1.0, normalizedId = 10055, name = "HU_BRKPEDLRRATPERC", mapping = emptyMap()),
            SignalSpec("property.10056", if (l6) 557855239 else 557855239, "raw", 1.0, normalizedId = 10056, name = "HU_BLTLOCKSTATDRVRBLTLOCKST1", mapping = emptyMap()),
            SignalSpec("property.10057", if (l6) 557849285 else 557849285, "raw", 1.0, normalizedId = 10057, name = "CLUSTER_ADAS_ASYAUTDRVCTRLTYP", mapping = emptyMap()),
            SignalSpec("steeringAngle", if (l6) 557849839 else 557849839, "°", 1.0, normalizedId = 10058, name = "CLUSTER_ARHUD_STEERWHLSNSRAG", mapping = emptyMap()),
            SignalSpec("property.10059", if (l6) 561024700 else 561024703, "raw", 1.0, normalizedId = 10059, name = "padtetypebutton", mapping = emptyMap()),
            SignalSpec("evRange", if (l6) 561025075 else 561025079, "km", 1.0, normalizedId = 10060, name = "patcdtehvbattindicated", mapping = emptyMap()),
            SignalSpec("property.10061", if (l6) 561024414 else 561024416, "L/100km", 10.0, normalizedId = 10061, name = "patripaverageconsumption100", mapping = emptyMap()),
            SignalSpec("property.10062", if (l6) 561024416 else 561024418, "kWh/100km", 10.0, normalizedId = 10062, name = "patripaverageenconsumption100", mapping = emptyMap()),
        )
    }
    fun convert(spec: SignalSpec, raw: Any?): String? {
        val value = (raw as? Number)?.toDouble() ?: return null
        if (!value.isFinite() || (value != 0.0 && kotlin.math.abs(value) < java.lang.Float.MIN_NORMAL)) return null
        val integral = value % 1.0 == 0.0
        if (spec.key == "gear") return if (integral) spec.mapping[value.toLong().toString()] else null
        if (integral) spec.mapping[value.toLong().toString()]?.let { return it }
        val normalized = value / spec.divisor
        val range = when (spec.key) {
            "displaySpeed" -> 0.0..300.0
            "soc", "fuel", "accelerator", "brake" -> 0.0..100.0
            "outsideTemperature", "insideTemperature" -> -60.0..100.0
            "rpm" -> 0.0..12000.0
            else -> spec.minimum..spec.maximum
        }
        return normalized.takeIf { it in range }?.toString()
    }
}
