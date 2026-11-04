"""固定HMI私有副本：保留原地址与两个LOAD段，不移动原厂机器码。"""
import struct
import lief
from elf64 import ELF


def patch(original, addon, destination, profile):
    elf = ELF(original)
    host = lief.ELF.parse(str(original))
    module = ELF(addon)
    symbols = {s.name: s for s in host.symtab_symbols}
    loads = [p for p in elf.ph if p['type'] == 1]
    if len(loads) != 2 or loads[1]['flags'] != 6:
        raise ValueError('只接受已核验的双LOAD原厂布局')
    data = loads[1]
    image = bytearray(elf.b)
    shoff, shsize, shcount, shstrings = elf.h[5], elf.h[10], elf.h[11], elf.h[12]
    if shsize != 64:
        raise ValueError('section ABI')
    sections = [list(struct.unpack_from('<IIQQQQIIQQ', image, shoff + i * shsize)) for i in range(shcount)]
    names = elf.take(sections[shstrings][4], sections[shstrings][5])
    def name(row):
        return names[row[0]:names.index(0, row[0])].decode()
    def append(payload, alignment=8):
        image.extend(b'\0' * (-len(image) % alignment))
        off = len(image)
        image.extend(payload)
        return off, off + data['vaddr'] - data['offset']
    # 扩展第二LOAD时，原NOBITS范围必须仍为零；重叠的调试节先完整迁到私有副本尾部。
    zero_start = data['offset'] + data['filesz']
    zero_end = data['offset'] + data['memsz']
    moved_debug = []
    for row in sections:
        if row[1] != 8 and row[5] and row[4] < zero_end and row[4] + row[5] > zero_start:
            if row[2] & 2:
                raise ValueError('BSS与已加载节重叠')
            row[4], _ = append(elf.take(row[4], row[5]), max(1, row[8]))
            moved_debug.append(name(row))
    image[zero_start:zero_end] = b'\0' * (zero_end - zero_start)
    strings = bytearray(elf.strtab)
    dyn = bytearray(elf.va(elf.one(6), len(elf.syms) * 24))
    indices = {s['name']: s['index'] for s in elf.syms}
    def add_string(value):
        off = len(strings); strings.extend(value.encode() + b'\0'); return off
    def symbol(value, defined):
        if value in indices:
            return indices[value]
        index = len(dyn) // 24
        if defined:
            s = symbols[value]
            entry = (add_string(value), 0x10 | int(s.type), 0, s.shndx, s.value, s.size)
        else:
            entry = (add_string(value), 0x12, 0, 0, 0, 0)
        dyn.extend(struct.pack('<IBBHQQ', *entry)); indices[value] = index
        return index
    imported = []
    libc_imports = set(profile['libcImports'])
    for entry in module.syms:
        value = entry['name']
        if entry['defined'] or not value or value in libc_imports:
            continue
        if value not in symbols or not symbols[value].value:
            raise ValueError('未经核验的引擎导入: ' + value)
        symbol(value, True)
        s = symbols[value]
        imported.append(dict(name=value, address=hex(s.value), size=s.size, candidateAddress=hex(s.value)))
    vtable = symbols[profile['applicationVtable']].value
    factory_got = int(profile['applicationFactoryVtableGot'], 16)
    factory_relocations = [r for r in elf.relocs if r['offset'] == factory_got]
    if len(factory_relocations) != 1 or factory_relocations[0]['type'] != 1027 or factory_relocations[0]['addend'] != vtable:
        raise ValueError('createApplication实际构造的虚表与选定挂接类型不符')
    edits = {}
    changes = []
    for offset, old, new in [
        (0x60, '_ZN5kanzi11Application8onUpdateENSt6chrono8durationIlSt5ratioILl1ELl1000EEEE', 'galaxy_music_on_update'),
        (0x48, '_ZN5kanzi11Application10onShutdownEv', 'galaxy_music_on_shutdown'),
    ]:
        address = vtable + offset
        rs = [r for r in elf.relocs if r['offset'] == address]
        if len(rs) != 1 or rs[0]['type'] != 1027 or rs[0]['addend'] != symbols[old].value or elf.va(symbols[old].value, 4) != bytes.fromhex('c0035fd6'):
            raise ValueError('固定空回调不符')
        edits[address] = symbol(new, False)
        changes.append(dict(vtableOffset=hex(offset), address=hex(address), old=old, new=new))
    records = []
    for r in elf.relocs:
        if r['table'] != 'RELA':
            continue
        index, kind, addend = r['symbol_index'], r['type'], r['addend']
        if r['offset'] in edits:
            index, kind, addend = edits[r['offset']], 257, 0
        records.append((kind, struct.pack('<QQq', r['offset'], index << 32 | kind, addend)))
    relative = [r for kind, r in records if kind == 1027]
    other = [r for kind, r in records if kind != 1027]
    # 单bucket GNU hash允许保持所有原符号索引，原PLT及其重定位无需改写。
    first = struct.unpack('<IIII', elf.va(elf.one(0x6ffffef5), 16))[1]
    def gnu_hash(value):
        h = 5381
        for b in value.encode(): h = (h * 33 + b) & 0xffffffff
        return h
    all_names = {i: n for n, i in indices.items()}
    hashes = [gnu_hash(all_names[i]) for i in range(first, len(dyn) // 24)]
    bloom = 0
    for h in hashes: bloom |= (1 << (h % 64)) | (1 << ((h >> 5) % 64))
    table = struct.pack('<IIIIQI', 1, first, 1, 5, bloom, first)
    table += b''.join(struct.pack('<I', (h & ~1) | (i == len(hashes)-1)) for i, h in enumerate(hashes))
    library_offset = add_string(profile.get('componentInstallPath', addon.name))
    payloads = {'.dynsym': bytes(dyn), '.dynstr': bytes(strings), '.gnu.hash': table, '.rela.dyn': b''.join(relative + other)}
    locations = {}
    for section, payload in payloads.items():
        off, address = append(payload)
        locations[section] = (off, address, len(payload))
    replacements = {6: locations['.dynsym'][1], 5: locations['.dynstr'][1], 10: len(strings), 0x6ffffef5: locations['.gnu.hash'][1], 7: locations['.rela.dyn'][1], 8: locations['.rela.dyn'][2], 0x6ffffff9: len(relative)}
    entries = [(d['tag'], replacements.get(d['tag'], d['value'])) for d in elf.dynamic]
    entries.append((1, library_offset))
    dynamic = b''.join(struct.pack('<qQ', tag, value) for tag, value in entries) + bytes(16)
    dp = next(p for p in elf.ph if p['type'] == 2)
    if len(dynamic) > dp['filesz']: raise ValueError('动态表保留空间不足')
    image[dp['offset']:dp['offset']+dp['filesz']] = dynamic.ljust(dp['filesz'], b'\0')
    for row in sections:
        n = name(row)
        if n in locations:
            row[4], row[3], row[5] = locations[n]
        if n == '.dynamic': row[5] = len(dynamic)
    for i, row in enumerate(sections): struct.pack_into('<IIQQQQIIQQ', image, shoff + i * shsize, *row)
    for i, p in enumerate(elf.ph):
        if p is data:
            size = len(image) - p['offset']
            struct.pack_into('<QQ', image, elf.phoff + i * elf.phent + 32, size, size)
    destination.write_bytes(image)
    check = ELF(destination)
    if check.va(data['vaddr']+data['filesz'], zero_end-zero_start) != bytes(zero_end-zero_start): raise ValueError('原BSS不再为零')
    # 第一LOAD仅允许第二LOAD的PHDR尺寸字段变化；其余原厂字节完全保留。
    expected_text = bytearray(elf.take(loads[0]['offset'], loads[0]['filesz']))
    actual_text = bytearray(check.take(loads[0]['offset'], loads[0]['filesz']))
    data_header = elf.phoff + elf.ph.index(data) * elf.phent
    expected_text[data_header + 32:data_header + 48] = actual_text[data_header + 32:data_header + 48]
    if actual_text != expected_text: raise ValueError('原厂TEXT段除段尺寸外发生变化')
    if len([p for p in check.ph if p['type']==1]) != 2: raise ValueError('LOAD数量改变')
    return imported, changes, len(relative), moved_debug
