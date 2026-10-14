#!/system/bin/sh

PACKAGE_CONFIG="/data/adb/ap/package_config"
TEMP_FILE="/data/adb/XiaoCaiYe/APatch临时文件.txt"
EXCLUSION_LIST="/data/adb/XiaoCaiYe/APatch排除列表.txt"
TEMP_FINAL="/data/adb/ap/package_config.new"

if [[ ! -f "$PACKAGE_CONFIG" || ! -s "$PACKAGE_CONFIG" ]]; then
	echo "APatch 配置文件不存在！"
	exit 1
fi

>"$TEMP_FILE"
>"$EXCLUSION_LIST"

cp -f "$PACKAGE_CONFIG" "$TEMP_FINAL"

sed -i -E '/^(me\.bmax\.apatch,|github\.ColdAsSunny\.Kernel,|me\.yuki\.folk,|mi\.yuki\.folk,)/d' "$TEMP_FINAL"

pm list packages -3 | sed 's/package://g' | grep -vE '^(me\.bmax\.apatch|github\.ColdAsSunny\.Kernel|me\.yuki\.folk|mi\.yuki\.folk)$' > \
	"$EXCLUSION_LIST"

while IFS= read -r package_name || [ -n "$package_name" ]; do
	package_name=$(echo "$package_name" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
	[ -z "$package_name" ] && continue
	[ "${package_name:0:1}" = "#" ] && continue
	case "$package_name" in
	me.bmax.apatch | github.ColdAsSunny.Kernel | me.yuki.folk | mi.yuki.folk) continue ;;
	esac
	for user_dir in /data/user/*; do
		user_id=$(basename "$user_dir")
		[ ! -d "$user_dir/$package_name" ] && continue
		awk -F',' -v pkg="$package_name" -v uid="$user_id" '
            $1==pkg && int($4/100000)==uid { found=1 }
            END { exit !found }
        ' "$TEMP_FINAL" && continue
		uid=$(stat -c '%u' "$user_dir/$package_name" 2>/dev/null)
		echo "$uid" | grep -qE '^[0-9]+$' || continue
		echo "$package_name,1,0,$uid,0,u:r:untrusted_app:s0" >>"$TEMP_FILE"
	done
done <"$EXCLUSION_LIST"

if [ -s "$TEMP_FILE" ]; then
	cat "$TEMP_FILE" >>"$TEMP_FINAL"
	sed -i -E '/^(me\.bmax\.apatch,|github\.ColdAsSunny\.Kernel,|me\.yuki\.folk,|mi\.yuki\.folk,)/d' "$TEMP_FINAL"
fi

if cmp -s "$PACKAGE_CONFIG" "$TEMP_FINAL" 2>/dev/null; then
	echo "当前排除列表已是最新"
else
	mv "$TEMP_FINAL" "$PACKAGE_CONFIG"
	echo "当前排除列表已更新"
fi

rm -f "$EXCLUSION_LIST" "$TEMP_FILE" "$TEMP_FINAL"
