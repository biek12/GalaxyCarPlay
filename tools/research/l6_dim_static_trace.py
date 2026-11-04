#!/usr/bin/env python3
"""只读追踪已采集L6 ELF/资源；不执行目标程序，不连接设备。

依赖 pyelftools==0.33、capstone==5.0.9。原厂反汇编输出仅保存于本地忽略目录。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from capstone import CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN, Cs
from elftools.elf.elffile import ELFFile


def fingerprint(path: Path) -> dict:
    return {"path": str(path), "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def disassemble(path: Path, pattern: str, output: Path) -> list[dict]:
    """只标注符号和直接BL/B目标，不把字符串邻近关系当成控制流证据。"""
    selected = []
    matcher = re.compile(pattern)
    with path.open("rb") as stream, output.open("w") as result:
        elf = ELFFile(stream)
        if elf['e_machine'] != 'EM_AARCH64':
            raise ValueError(f"不是AArch64: {path.name}")
        symbols, names = {}, {}
        for section in elf.iter_sections():
            if section['sh_type'] not in ('SHT_SYMTAB', 'SHT_DYNSYM'):
                continue
            for symbol in section.iter_symbols():
                if symbol.name and symbol['st_value']:
                    names[symbol['st_value']] = symbol.name
                    if symbol['st_info']['type'] == 'STT_FUNC' and symbol['st_size']:
                        symbols[symbol.name] = (symbol['st_value'], symbol['st_size'])
        plt, relocations = elf.get_section_by_name('.plt'), elf.get_section_by_name('.rela.plt')
        if plt is not None and relocations is not None:
            table = elf.get_section(relocations['sh_link'])
            # 本批目标为标准AArch64 PLT：32字节头、16字节entry。
            if plt['sh_size'] != 32 + 16 * relocations.num_relocations():
                raise ValueError(f"未核验PLT布局: {path.name}")
            for index, relocation in enumerate(relocations.iter_relocations()):
                names[plt['sh_addr'] + 32 + 16 * index] = (
                    'PLT:' + table.get_symbol(relocation['r_info_sym']).name)
        decoder = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        for name, (address, size) in sorted(symbols.items(), key=lambda item: item[1][0]):
            if not matcher.search(name):
                continue
            section = next(section for section in elf.iter_sections()
                           if section['sh_addr'] <= address
                           and address + size <= section['sh_addr'] + section['sh_size'])
            stream.seek(section['sh_offset'] + address - section['sh_addr'])
            instructions = list(decoder.disasm(stream.read(size), address))
            if sum(ins.size for ins in instructions) != size:
                raise ValueError(f"函数未完整解码: {name}")
            selected.append({"symbol": name, "address": hex(address), "size": size})
            result.write(f"\nFUNCTION {name} {address:#x} size={size}\n")
            for instruction in instructions:
                annotation = ''
                if instruction.mnemonic in ('bl', 'b'):
                    target = int(instruction.op_str.lstrip('#'), 0)
                    annotation = ' ; ' + names.get(target, '')
                result.write(f"{instruction.address:#010x}: {instruction.mnemonic:8} "
                             f"{instruction.op_str}{annotation}\n")
    return selected


def transport_imports(root: Path) -> dict:
    """有界列举指定动态导入；阴性结果不涵盖静态链接/动态查符号/其他固件。"""
    matcher = re.compile(r'habmm|mmap_peer|pmem_(import|export)|'
                         r'screen_(share|attach|create_pixmap|create_stream|acquire|join_window)|'
                         r'eglCreateImage')
    count, matches, errors = 0, [], []
    for path in sorted(root.rglob('*')):
        if path.is_symlink() or not path.is_file():
            continue
        try:
            with path.open('rb') as stream:
                if stream.read(4) != b'\x7fELF':
                    continue
                count += 1
                stream.seek(0)
                elf = ELFFile(stream)
                section = elf.get_section_by_name('.dynsym')
                if section is None:
                    continue
                hits = sorted({symbol.name for symbol in section.iter_symbols()
                               if symbol['st_shndx'] == 'SHN_UNDEF' and matcher.search(symbol.name)})
                if hits:
                    matches.append({"path": str(path.relative_to(root)), "imports": hits})
        except Exception as error:
            errors.append({"path": str(path.relative_to(root)), "type": type(error).__name__})
    return {"elfCount": count, "matches": matches, "errors": errors}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('.work/research/l6-dim-static-trace'))
    args = parser.parse_args()
    root, output = args.rootfs.resolve(), args.output.resolve()
    if root == output or root in output.parents:
        parser.error('输出不得写入原始rootfs')
    output.mkdir(parents=True, exist_ok=True)
    cluster = root / 'apps/cluster/FX11_HEV'
    hmi = cluster / 'bin/hmi/GLY_G733/ClusterHMI'
    jobs = [
        (hmi / 'cluster-hmi', 'hmi', r'FullMapStatusNotify6Notify|'
         r'FunctionMultiMediaInfoManager(4init|C1)|NaviTack[123]Nofity6Notify'),
        (cluster / 'lib/libMiddleware.so', 'middleware', r'FullMap|'
         r'HMIStateServer(C1|11OnRegNotify)|StateRightMenuNavigation.*(start|update)|'
         r'ClusterIVIServer.*(onInvoke|AndroidSendToQnx|SendEntertainment|8SendNavi)|'
         r'IVIInteractionServer.*NavigationStatus'),
    ]
    report = {"scope": "local-static-only", "inputs": [], "functions": {}, "resources": {}}
    for path, label, pattern in jobs:
        report['inputs'].append(fingerprint(path))
        report['functions'][label] = disassemble(path, pattern, output / f'{label}.S.txt')
    for name in ('ClusterDataSource.xml', 'rightmenu.kzb.txt', 'background.kzb.txt',
                 'clusterfunction.kzb.txt', 'adas.kzb.txt'):
        path = hmi / name
        report['inputs'].append(fingerprint(path))
        report['resources'][name] = [
            {"line": index, "text": line.strip()}
            for index, line in enumerate(path.read_text().splitlines(), 1)
            if re.search(r'FullMap|Navi|MapTexture|MapVideo|VideoStream|ExternalTexture', line)]
    report['transportImports'] = transport_imports(root)
    destination = output / 'report.json'
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({"report": str(destination),
                      "elfCount": report['transportImports']['elfCount'],
                      "errors": len(report['transportImports']['errors']),
                      "functions": {key: len(value) for key, value in report['functions'].items()}},
                     ensure_ascii=False))


if __name__ == '__main__':
    main()
