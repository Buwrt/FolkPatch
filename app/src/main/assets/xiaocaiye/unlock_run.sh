#!/system/bin/sh

set -x

# 获取当前模块目录路径
MODDIR=${0%/*}

# 等待解锁
while [ "$(/system/bin/app_process -Djava.class.path=$MODDIR/isKeyguardLocked.dex /system/bin com.rosan.shell.ActiviteJava)" == "true" ]; do
	sleep 1
done

while [ "$(getprop sys.boot_completed)" != "1" ]; do
	sleep 1
done

for i in 1 2 3 4 5; do
	result=$(am start -n com.tsng.hidemyapplist/icu.nullptr.hidemyapplist.ui.activity.MainActivity 2>&1)
	echo "$result"
	echo "$result" | grep -qv "Failure\|Error" && break
	sleep 5
done
