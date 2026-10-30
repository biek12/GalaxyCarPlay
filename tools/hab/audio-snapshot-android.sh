#!/system/bin/sh
# 仅取有限日志中的音频状态/计数；不录音、不抓包，不保存完整协议字段。
logcat -d -v threadtime -t 1500 -s xcertplay-usb:I '*:S' |
    sed -n '/Microphone: /p; /Audio: mode /p; /Audio: focus /p; /Audio: setup /s/ keys=.*//p'
