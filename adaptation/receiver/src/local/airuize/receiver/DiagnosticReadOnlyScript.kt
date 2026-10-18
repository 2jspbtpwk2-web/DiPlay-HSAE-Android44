package local.airuize.receiver

/** Two fixed read-only inventories. Every potentially blocking native command is a
 * direct child of this wrapper. The private stop watcher verifies PID/PPid/starttime
 * before signalling that child; it never searches for or kills another owner's process. */
internal object DiagnosticReadOnlyScript {
    const val PACKAGE_PLACEHOLDER="PM_PKGS_APPLICATION_SNAPSHOT"

    fun networkPolicy(basePath:String):String=wrap(basePath,"""
echo IPV6_POLICY_BEGIN
echo '--ipv6-rules--'; diag_run ip -6 rule show
echo '--ipv6-routes--'; diag_run ip -6 route show table all
echo '--ipv6-neighbours--'; diag_run ip -6 neigh show
echo '--ipv6-addresses--'; diag_run ip -6 addr show
echo '--ipv6-disabled--'; diag_run cat /proc/sys/net/ipv6/conf/all/disable_ipv6 /proc/sys/net/ipv6/conf/default/disable_ipv6
echo '--ipv6-protocol-counters--'; diag_run cat /proc/net/snmp6
echo '--ipv6-filter-input--'; diag_run ip6tables -n -v -L INPUT
echo '--ipv6-filter-output--'; diag_run ip6tables -n -v -L OUTPUT
echo '--ipv6-filter-forward--'; diag_run ip6tables -n -v -L FORWARD
echo IPV6_POLICY_END
""".trimIndent())

    fun rootDeep(basePath:String):String=wrap(basePath,"""
echo ROOT_BEGIN_9f3a
echo '@@@SECTION:ID@@@'; diag_run id
echo '@@@SECTION:SVC_CHECK@@@'
diag_run service check bluetooth_manager
diag_run service check usb
diag_run service check wifi
diag_run service check audio
diag_run service check connectivity
diag_run service check input
diag_run service check skypine_keyservice
echo '@@@SECTION:SVC_LIST@@@'; diag_run service list
echo '@@@SECTION:PS@@@'; diag_run ps
echo '@@@SECTION:PM_PKGS@@@'
echo 'PM_PKGS_SOURCE=PackageManager.getInstalledPackages(0); application API, no vendor pm shell subprocess'
echo '$PACKAGE_PLACEHOLDER'
echo '@@@SECTION:D_USB@@@'; diag_run dumpsys usb
echo '@@@SECTION:D_CONN@@@'; diag_run dumpsys connectivity
echo '@@@SECTION:D_AF@@@'; diag_run dumpsys media.audio_flinger
echo '@@@SECTION:D_AP@@@'; diag_run dumpsys media.audio_policy
echo '@@@SECTION:D_AUDIO@@@'; diag_run dumpsys audio
echo '@@@SECTION:D_WINDOW@@@'; diag_run dumpsys window windows
echo '@@@SECTION:IP_RULE@@@'; diag_run ip rule; echo '--ipv6_route--'; diag_run cat /proc/net/ipv6_route
echo '@@@SECTION:USB_DEBUG@@@'; diag_run cat /sys/kernel/debug/usb/devices
echo '--sysfs--'; diag_run ls -l /sys/bus/usb/devices
echo '--devbus--'; diag_run ls -lR /dev/bus/usb
echo '--tun--'; diag_run ls -l /dev/tun
echo '@@@SECTION:USB_ALT_STATE@@@'
for d in /sys/bus/usb/devices/*; do
  diag_check_stop
  [ -f "${'$'}d/idVendor" ] || continue
  diag_read "${'$'}d/idVendor"; V=${'$'}diag_value
  [ "${'$'}V" = 05ac ] || [ "${'$'}V" = 05AC ] || continue
  diag_read "${'$'}d/idProduct"; product=${'$'}diag_value
  diag_read "${'$'}d/busnum"; bus=${'$'}diag_value
  diag_read "${'$'}d/devnum"; dev=${'$'}diag_value
  diag_read "${'$'}d/bConfigurationValue"; config=${'$'}diag_value
  echo "device=${'$'}d vid=${'$'}V pid=${'$'}product bus=${'$'}bus dev=${'$'}dev config=${'$'}config"
  for i in "${'$'}d":*; do
    diag_check_stop
    [ -d "${'$'}i" ] || continue
    diag_read "${'$'}i/bInterfaceNumber"; number=${'$'}diag_value
    diag_read "${'$'}i/bAlternateSetting"; alt=${'$'}diag_value
    diag_read "${'$'}i/bInterfaceClass"; class=${'$'}diag_value
    diag_read "${'$'}i/bInterfaceSubClass"; sub=${'$'}diag_value
    diag_read "${'$'}i/bInterfaceProtocol"; proto=${'$'}diag_value
    diag_read "${'$'}i/bNumEndpoints"; endpoints=${'$'}diag_value
    echo "  interface=${'$'}i number=${'$'}number alt=${'$'}alt class=${'$'}class sub=${'$'}sub proto=${'$'}proto endpoints=${'$'}endpoints"
    diag_run ls -l "${'$'}i/driver"
  done
done
echo '@@@SECTION:INPUT_DEV@@@'; diag_run cat /proc/bus/input/devices
echo '--getevent-lp--'; diag_run getevent -lp
echo '--dumpsys-input--'; diag_run dumpsys input
echo '@@@SECTION:KEYLAYOUT@@@'; diag_run ls -l /system/usr/keylayout /vendor/usr/keylayout /data/system/devices/keylayout
for f in /system/usr/keylayout/*.kl /vendor/usr/keylayout/*.kl /data/system/devices/keylayout/*.kl; do
  diag_check_stop
  [ -f "${'$'}f" ] || continue
  echo "--key mappings in ${'$'}f--"
  diag_run grep -E '^[[:space:]]*key[[:space:]]' "${'$'}f"
done
echo '@@@SECTION:DMESG@@@'; diag_run dmesg
echo '@@@SECTION:LOG_CRASH@@@'; diag_run logcat -d -b crash -v time
echo '@@@SECTION:LOG_MAIN@@@'; diag_run logcat -d -v time
echo ROOT_END_9f3a
""".trimIndent())

    // Kept private: production accepts only the two fixed inventories above. Runtime
    // regression invokes this same wrapper by reflection with a controlled sleep child.
    private fun wrap(basePath:String,body:String):String="""
diag_base=${quote(basePath)}
diag_stop="${'$'}diag_base.stop"
diag_active="${'$'}diag_base.active"
diag_done="${'$'}diag_base.done"
diag_owner=${'$'}${'$'}
diag_child=''
diag_child_start=''
diag_watcher=''
diag_failed=0
diag_identity() {
  diag_seen_ppid=''; diag_seen_start=''; diag_line=''
  case "${'$'}1" in ''|*[!0-9]*) return 1 ;; esac
  IFS= read -r diag_line < "/proc/${'$'}1/stat" 2>/dev/null || return 1
  diag_rest=${'$'}{diag_line##*) }
  [ "${'$'}diag_rest" != "${'$'}diag_line" ] || return 1
  set -- ${'$'}diag_rest
  [ "${'$'}#" -ge 20 ] || return 1
  diag_seen_ppid=${'$'}2
  shift 19
  diag_seen_start=${'$'}1
  case "${'$'}diag_seen_ppid:${'$'}diag_seen_start" in *[!0-9:]*|:|:*) return 1 ;; esac
  [ -n "${'$'}diag_seen_start" ]
}
diag_kill_owned() {
  diag_kill_pid=${'$'}1; diag_kill_start=${'$'}2
  [ -n "${'$'}diag_kill_start" ] || return 1
  diag_identity "${'$'}diag_kill_pid" || return 1
  [ "${'$'}diag_seen_ppid" = "${'$'}diag_owner" ] && [ "${'$'}diag_seen_start" = "${'$'}diag_kill_start" ] || return 1
  kill "${'$'}diag_kill_pid" 2>/dev/null
  sleep 1
  diag_identity "${'$'}diag_kill_pid" || return 0
  [ "${'$'}diag_seen_ppid" = "${'$'}diag_owner" ] && [ "${'$'}diag_seen_start" = "${'$'}diag_kill_start" ] || return 0
  kill -9 "${'$'}diag_kill_pid" 2>/dev/null
}
diag_cleanup() {
  trap - EXIT HUP INT TERM
  : > "${'$'}diag_stop"
  if [ -n "${'$'}diag_child" ]; then
    diag_kill_owned "${'$'}diag_child" "${'$'}diag_child_start"
    wait "${'$'}diag_child" 2>/dev/null
    diag_child=''; diag_child_start=''
  fi
  [ -z "${'$'}diag_watcher" ] || wait "${'$'}diag_watcher" 2>/dev/null
  : > "${'$'}diag_active"
  echo "DIAG_CLEANUP=COMPLETE wrapperPid=${'$'}diag_owner activeChildReaped=true watchdogReaped=true" > "${'$'}diag_done"
}
trap 'diag_cleanup' EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
trap 'exit 129' HUP
[ ! -e "${'$'}diag_stop" ] || { echo DIAG_CANCELLED_BEFORE_START; exit 143; }
diag_identity "${'$'}diag_owner" || { echo DIAG_PROC_IDENTITY_UNAVAILABLE; exit 1; }
: > "${'$'}diag_active" || { echo DIAG_PRIVATE_FILE_UNWRITABLE; exit 1; }
(
  trap - EXIT HUP INT TERM
  while [ ! -e "${'$'}diag_stop" ]; do sleep 1; done
  watched_pid=''; watched_start=''
  IFS=' ' read -r watched_pid watched_start < "${'$'}diag_active" 2>/dev/null
  diag_kill_owned "${'$'}watched_pid" "${'$'}watched_start"
  exit 0
) &
diag_watcher=${'$'}!
diag_check_stop() { [ ! -e "${'$'}diag_stop" ] || { echo DIAG_CANCELLED; exit 143; }; }
diag_run() {
  diag_check_stop
  "${'$'}@" 2>&1 &
  diag_child=${'$'}!
  diag_child_start=''
  if diag_identity "${'$'}diag_child" && [ "${'$'}diag_seen_ppid" = "${'$'}diag_owner" ]; then
    diag_child_start=${'$'}diag_seen_start
    echo "${'$'}diag_child ${'$'}diag_child_start" > "${'$'}diag_active"
  fi
  if [ -e "${'$'}diag_stop" ]; then diag_kill_owned "${'$'}diag_child" "${'$'}diag_child_start"; fi
  wait "${'$'}diag_child" 2>/dev/null
  diag_rc=${'$'}?
  diag_child=''; diag_child_start=''
  : > "${'$'}diag_active"
  echo "DIAG_RUN exit=${'$'}diag_rc command=${'$'}1"
  [ "${'$'}diag_rc" -eq 0 ] || diag_failed=1
  diag_check_stop
}
diag_read() {
  diag_check_stop
  diag_value=''
  IFS= read -r diag_value < "${'$'}1" 2>/dev/null || { [ -n "${'$'}diag_value" ] || diag_value="<unreadable:${'$'}1>"; }
}
echo DIAG_PRIVATE_WRAPPER_BEGIN
$body
echo "DIAG_PRIVATE_WRAPPER_END failedCommands=${'$'}diag_failed"
exit "${'$'}diag_failed"
""".trimIndent()

    private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
}
