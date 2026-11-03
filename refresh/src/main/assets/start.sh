#!/system/bin/sh
pkg=com.roylyl.tools
apk=$(pm path "$pkg" | head -n 1 | cut -d: -f2)
[ -n "$apk" ] || exit 1
existing=$(pidof adaptivehz_server)
if [ -n "$existing" ]; then
    kill $existing
    sleep 1
fi
CLASSPATH="$apk" app_process /system/bin --nice-name=adaptivehz_server rikka.shizuku.server.ShizukuService >/data/local/tmp/adaptivehz-backend.log 2>&1 </dev/null &
echo "Backend launching"
