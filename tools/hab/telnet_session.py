#!/usr/bin/env python3
"""显式地址的交互Telnet诊断通道。stdin为JSON命令，不保存认证，不自动登录或执行动作。"""
import argparse
import base64
from pathlib import Path
import gzip
import hashlib
import json
import re
import shlex
import select
import secrets
import socket
import subprocess
import sys
import struct
import time
from zipfile import ZipFile


class Channel:
    def __init__(self, host, port):
        self.socket = socket.create_connection((host, port), timeout=8)
        self.pending = bytearray()
        self.redactions = []

    def decode(self, data):
        self.pending.extend(data)
        output = bytearray()
        while self.pending:
            if self.pending[0] != 255:
                output.append(self.pending.pop(0))
                continue
            if len(self.pending) < 2:
                break
            command = self.pending[1]
            if command == 255:
                output.append(255); del self.pending[:2]
            elif command in (251, 252, 253, 254):
                if len(self.pending) < 3:
                    break
                option = self.pending[2]
                if command == 251:
                    self.socket.sendall(bytes([255, 253 if option in (1, 3) else 254, option]))
                elif command == 253:
                    self.socket.sendall(bytes([255, 252, option]))
                del self.pending[:3]
            elif command == 250:
                end = self.pending.find(b"\xff\xf0", 2)
                if end < 0:
                    break
                del self.pending[:end + 2]
            else:
                del self.pending[:2]
        return output.decode("utf-8", "replace")

    def receive(self, seconds, until=None, *, redact=True):
        end = time.monotonic() + seconds
        output = ""
        while time.monotonic() < end:
            ready, _, _ = select.select([self.socket], [], [], min(.3, end - time.monotonic()))
            if not ready:
                continue
            data = self.socket.recv(65536)
            if not data:
                break
            output += self.decode(data)
            if until and until in output:
                break
        if redact:
            for secret in self.redactions:
                output = output.replace(secret, "[REDACTED]")
        return output


def authorized_qnx_credentials(apk):
    """沿前次核验的外部OEM样本读取认证，只使用账号/密码，不执行样本中的录音/删除工作流。"""
    raw = Path(apk).read_bytes()
    if hashlib.sha256(raw).hexdigest() != "1c6c9069e7364d75128d507cdc26fe95068bf8ef6846d5abc5af7a4eb63b2e7b":
        raise ValueError("OEM认证来源指纹不符；未尝试登录")
    workflows = []
    with ZipFile(Path(apk)) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            data = archive.read(name)
            count, offset = struct.unpack_from("<II", data, 56)
            if count > 1000000 or offset + count * 4 > len(data):
                raise ValueError("DEX边界无效")
            for index in range(count):
                position = struct.unpack_from("<I", data, offset + index * 4)[0]
                for _ in range(5):
                    byte = data[position]; position += 1
                    if not byte & 128:
                        break
                text = data[position:data.index(0, position)].decode("utf-8", "replace")
                if "busybox telnet 192.168.118.2" in text:
                    workflows.append(re.findall(r'echo "([^\"]*)"', text)[:2])
    if len(workflows) != 2 or workflows[0] != workflows[1] or len(workflows[0]) != 2:
        raise ValueError("认证流程与已核验版本不符")
    return tuple(workflows[0])


def qnx_login(channel, apk):
    username, password = authorized_qnx_credentials(apk)
    channel.redactions.extend([password])
    channel.socket.sendall(b"busybox telnet 192.168.118.2\r\n")
    if "login:" not in channel.receive(8, "login:"):
        raise ValueError("QNX未出现login提示")
    channel.socket.sendall(username.encode() + b"\r\n")
    username_response = channel.receive(5)
    if "password" not in username_response.lower():
        raise ValueError("QNX用户名阶段未出现密码提示: " + username_response[-120:])
    channel.socket.sendall(password.encode() + b"\r\n")
    result = channel.receive(5)
    if "# " not in result and "No home" not in result:
        raise ValueError("QNX登录未确认")
    return {"qnx_authenticated": True, "credentials_logged": False}


def provision_autoservice_qnx(channel, external_apk):
    """仅由显式外部已核验来源配置设备App私有目录；不输出或写本机认证副本。"""
    username, password = authorized_qnx_credentials(external_apk)
    payload = base64.b64encode(json.dumps(dict(username=username, password=password)).encode()).decode()
    channel.redactions.extend([password, payload])
    command = ("printf '%s' '" + payload + "' | run-as com.ecarx.autoservice sh -c " +
               shlex.quote("umask 077; mkdir -p no_backup; base64 -d > no_backup/qnx-login.json") +
               " && echo AUTOSERVICE_QNX_CONFIGURED\r\n")
    channel.socket.sendall(command.encode())
    result = channel.receive(8, "\r\nAUTOSERVICE_QNX_CONFIGURED\r\n")
    if "\r\nAUTOSERVICE_QNX_CONFIGURED\r\n" not in result:
        raise ValueError("AutoService私有QNX配置未确认；未输出认证内容")
    return dict(autoservice_qnx_configured=True, credentials_logged=False, credentials_in_apk=False)


def provision_app_hab(channel, request):
    """显式操作：应用自生成key只在内存中转至自有QNX端，不落本机、不打印正文。"""
    destination = request["qnx_destination"]
    if not re.fullmatch(r"/var/data/galaxy-hab-[A-Za-z0-9_-]+/app-owner\.key", destination):
        raise ValueError("key只能配置到已部署的独立/var/data/galaxy-hab-*/app-owner.key")
    channel.socket.sendall(b"echo GALAXY_KEY_BEGIN; run-as com.ecarx.autoservice base64 no_backup/hab/owner.key; echo GALAXY_KEY_END\r\n")
    raw = channel.receive(10, "\r\nGALAXY_KEY_END\r\n", redact=False)
    try:
        encoded = raw.split("\r\nGALAXY_KEY_BEGIN\r\n", 1)[1].split("\r\nGALAXY_KEY_END\r\n", 1)[0]
        key = base64.b64decode("".join(encoded.split()), validate=True)
    except (IndexError, ValueError) as error:
        raise ValueError("未取得本应用HAB key；先打开新版CarPlay投屏页，不能使用测试fixture代替") from error
    if len(key) != 32:
        raise ValueError("应用HAB key长度无效；未写入QNX")
    encoded = base64.b64encode(key).decode()
    channel.redactions.append(encoded)
    # 目标QNX没有base64；只沿已实测的Android NFS→QNX /otaupdate文件桥。
    filename = "galaxy-hab-key-" + secrets.token_hex(12) + ".bin"
    stage = "/data/vendor/nfs/nfs_ota/" + filename
    shared = "/otaupdate/" + filename
    channel.socket.sendall(("(umask 077; run-as com.ecarx.autoservice cat no_backup/hab/owner.key > " +
                            shlex.quote(stage) + ") && echo GALAXY_KEY_STAGED\r\n").encode())
    output = channel.receive(10, "\r\nGALAXY_KEY_STAGED\r\n")
    if "\r\nGALAXY_KEY_STAGED\r\n" not in output:
        raise ValueError("应用key暂存失败；未登录QNX或启动接收端")
    expected = subprocess.run(["cksum"], input=key, capture_output=True, check=True).stdout.decode().split()[:2]
    qnx_login(channel, request["qnx_auth_apk"])
    directory = shlex.quote(str(Path(destination).parent))
    target = shlex.quote(destination)
    channel.socket.sendall((f"test -d {directory} && cp {shlex.quote(shared)} {target} && chmod 600 {target} && "
                            "echo GALAXY_KEY_WRITTEN\r\n").encode())
    output = channel.receive(10, "\r\nGALAXY_KEY_WRITTEN\r\n")
    if "\r\nGALAXY_KEY_WRITTEN\r\n" not in output:
        raise ValueError("QNX key写入失败；未启动接收端")
    channel.socket.sendall(f"echo GALAXY_VERIFY_BEGIN; cksum {target}; rm -f {shlex.quote(shared)}; echo GALAXY_VERIFY_END\r\n".encode())
    raw = channel.receive(10, "\r\nGALAXY_VERIFY_END\r\n", redact=False)
    try:
        checksum = raw.split("\r\nGALAXY_VERIFY_BEGIN\r\n", 1)[1].split("\r\nGALAXY_VERIFY_END\r\n", 1)[0]
        received = checksum.split()[:2]
    except (IndexError, ValueError) as error:
        raise ValueError("QNX key回读无效；未启动接收端") from error
    if received != expected:
        raise ValueError("QNX key回读不一致；未启动接收端")
    return {"app_hab_key_provisioned": True, "bytes": 32, "key_logged": False, "receiver_started": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=23)
    args = parser.parse_args()
    channel = Channel(args.host, args.port)
    print(json.dumps({"connected": True, "output": channel.receive(2)}, ensure_ascii=False), flush=True)
    for line in sys.stdin:
        try:
            request = json.loads(line)
            if request.get("close"):
                break
            if "provision_app_hab" in request:
                print(json.dumps(provision_app_hab(channel, request["provision_app_hab"])), flush=True)
                continue
            if "configure_autoservice_qnx_from" in request:
                print(json.dumps(provision_autoservice_qnx(channel, request["configure_autoservice_qnx_from"])), flush=True)
                continue
            if "qnx_auth_apk" in request:
                print(json.dumps(qnx_login(channel, request["qnx_auth_apk"])), flush=True)
                continue
            if "download" in request:
                source = shlex.quote(request["download"]["source"])
                destination = Path(request["download"]["destination"])
                channel.socket.sendall(f"echo downloadbegin; gzip -c {source} | base64; echo downloadend\r\n".encode())
                output = channel.receive(20, "\r\ndownloadend\r\n")
                encoded = output.split("\r\ndownloadbegin\r\n", 1)[1].split("\r\ndownloadend\r\n", 1)[0]
                data = gzip.decompress(base64.b64decode(encoded))
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                print(json.dumps({"downloaded_bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}), flush=True)
                continue
            if "upload" in request:
                source = Path(request["upload"]["source"])
                destination = shlex.quote(request["upload"]["destination"])
                payload = base64.b64encode(gzip.compress(source.read_bytes(), mtime=0)).decode()
                channel.socket.sendall(f": > {destination}.b64\r\n".encode())
                for offset in range(0, len(payload), 600):
                    command = f"printf '%s' '{payload[offset:offset+600]}' >> {destination}.b64\r\n"
                    channel.socket.sendall(command.encode())
                channel.socket.sendall(f"base64 -d {destination}.b64 | gzip -d > {destination}; echo upload_done\r\n".encode())
                output = channel.receive(20, "\r\nupload_done\r\n")
                print(json.dumps({"uploaded_local_bytes": source.stat().st_size, "output_tail": output[-900:]}, ensure_ascii=False), flush=True)
                continue
            if "command" in request:
                channel.socket.sendall(request["command"].encode() + b"\r\n")
            output = channel.receive(min(float(request.get("timeout", 3)), 50), request.get("until"))
            print(json.dumps({"output": output}, ensure_ascii=False), flush=True)
        except (OSError, ValueError) as error:
            print(json.dumps({"error": str(error)}), flush=True)
            if "qnx_auth_apk" in request:
                break
    channel.socket.close()


if __name__ == "__main__":
    main()
