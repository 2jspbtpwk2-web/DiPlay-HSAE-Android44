package local.airuize.receiver

/** Finite owned-child key capture. Production always uses the default 42 second window. */
internal object DiagnosticKeyCaptureScript {
    @JvmOverloads fun build(basePath:String,windowSeconds:Int=42):String {
        require(windowSeconds in 1..42)
        return """
                    echo ROOT_KEY_CAPTURE_BEGIN
                    ${HsaeKeyDiagnostics.rootCaptureStartShell(basePath)}
                    base=${quote(basePath)}
                    stop="${'$'}base.stop"
                    loop=''
                    key_loop_waited=false
                    key_loop_reaped=false
                    key_loop_rc=not-started
                    key_identity() {
                      key_ppid=''; key_start=''; key_line=''
                      case "${'$'}1" in ''|*[!0-9]*) return 1 ;; esac
                      IFS= read -r key_line < "/proc/${'$'}1/stat" 2>/dev/null || return 1
                      key_rest=${'$'}{key_line##*) }
                      [ "${'$'}key_rest" != "${'$'}key_line" ] || return 1
                      set -- ${'$'}key_rest
                      [ "${'$'}#" -ge 20 ] || return 1
                      key_ppid=${'$'}2; shift 19; key_start=${'$'}1
                      case "${'$'}key_ppid:${'$'}key_start" in *[!0-9:]*|:|:*) return 1 ;; esac
                      [ -n "${'$'}key_start" ]
                    }
                    key_signal_owned() {
                      key_signal_pid=${'$'}1; key_signal_start=${'$'}2; key_signal_parent=${'$'}3
                      [ -n "${'$'}key_signal_start" ] && [ -n "${'$'}key_signal_parent" ] && key_identity "${'$'}key_signal_pid" || return
                      [ "${'$'}key_ppid" = "${'$'}key_signal_parent" ] && [ "${'$'}key_start" = "${'$'}key_signal_start" ] || return
                      kill "${'$'}key_signal_pid" 2>/dev/null
                      sleep 1
                      key_identity "${'$'}key_signal_pid" || return
                      [ "${'$'}key_ppid" = "${'$'}key_signal_parent" ] && [ "${'$'}key_start" = "${'$'}key_signal_start" ] || return
                      kill -9 "${'$'}key_signal_pid" 2>/dev/null
                    }
                    key_stop_child() {
                      child=''; child_start=''
                      [ -n "${'$'}loop" ] && [ -f "${'$'}base.pid" ] || return
                      IFS=' ' read -r child child_start < "${'$'}base.pid"
                      key_signal_owned "${'$'}child" "${'$'}child_start" "${'$'}loop"
                    }
                    key_wait_loop() {
                      if [ -z "${'$'}loop" ]; then key_loop_reaped=true; return; fi
                      if [ "${'$'}key_loop_waited" = false ]; then
                        wait "${'$'}loop" 2>/dev/null
                        key_loop_rc=${'$'}?
                        key_loop_waited=true
                        [ "${'$'}key_loop_rc" -ne 0 ] || key_loop_reaped=true
                      fi
                    }
                    cleanup_keys() {
                      : > "${'$'}stop"
                      key_stop_child
                      key_wait_loop
                      if [ "${'$'}key_loop_reaped" = true ]; then
                        rm -f "${'$'}base.out" "${'$'}base.pid" "${'$'}stop"
                        echo "KEY_CLEANUP=COMPLETE geteventLoopReaped=true loopExit=${'$'}key_loop_rc" > "${'$'}base.done"
                      else
                        echo "KEY_CLEANUP=INCOMPLETE geteventLoopReaped=false loopExit=${'$'}key_loop_rc" > "${'$'}base.done"
                      fi
                    }
                    trap 'cleanup_keys' EXIT
                    trap 'exit 143' TERM
                    trap 'exit 130' INT
                    trap 'exit 129' HUP
                    [ ! -e "${'$'}stop" ] || exit 1
                    : > "${'$'}base.out" || { echo PRIVATE_CAPTURE_FILE_UNWRITABLE; exit 1; }
                    (
                      trap - EXIT HUP INT TERM
                      key_loop_pid=''; key_self_rest=''
                      IFS=' ' read -r key_loop_pid key_self_rest < /proc/self/stat || { echo GETEVENT_LOOP_IDENTITY_UNAVAILABLE >> "${'$'}base.out"; exit 1; }
                      case "${'$'}key_loop_pid" in ''|*[!0-9]*) echo GETEVENT_LOOP_IDENTITY_UNAVAILABLE >> "${'$'}base.out"; exit 1 ;; esac
                      batches=0
                      while [ ! -e "${'$'}stop" ]; do
                        getevent -lt -c 4 >> "${'$'}base.out" 2>&1 &
                        child=${'$'}!
                        child_start=''
                        if key_identity "${'$'}child" && [ "${'$'}key_ppid" = "${'$'}key_loop_pid" ]; then child_start=${'$'}key_start; fi
                        echo "${'$'}child ${'$'}child_start" > "${'$'}base.pid"
                        if [ -e "${'$'}stop" ]; then key_signal_owned "${'$'}child" "${'$'}child_start" "${'$'}key_loop_pid"; fi
                        wait "${'$'}child" 2>/dev/null
                        rc=${'$'}?
                        rm -f "${'$'}base.pid"
                        if [ -e "${'$'}stop" ]; then break; fi
                        if [ "${'$'}rc" -ne 0 ]; then echo "GETEVENT_EXIT=${'$'}rc" >> "${'$'}base.out"; break; fi
                        batches=${'$'}((batches + 1))
                        if [ "${'$'}batches" -ge 160 ]; then echo '[原始采集达到160批上限]' >> "${'$'}base.out"; break; fi
                      done
                    ) &
                    loop=${'$'}!
                    seconds=0
                    while [ "${'$'}seconds" -lt ${windowSeconds} ] && [ ! -e "${'$'}stop" ]; do
                      sleep 1
                      seconds=${'$'}((seconds + 1))
                    done
                    : > "${'$'}stop"
                    key_stop_child
                    key_wait_loop
                    echo "GETEVENT_LOOP_EXIT=${'$'}key_loop_rc"
                    echo '-- EV_KEY / EV_MSC --'
                    n=0
                    while IFS= read -r line; do
                      case "${'$'}line" in
                        *EV_KEY*|*EV_MSC*|*' 0001 '*|*' 0004 '*)
                          echo "${'$'}line"
                          n=${'$'}((n + 1))
                          if [ "${'$'}n" -ge 320 ]; then echo '[超过320条，后续省略]'; break; fi
                          ;;
                      esac
                    done < "${'$'}base.out"
                    echo "KEY_EVENT_LINES=${'$'}n"
                    echo '-- 采集状态 --'
                    while IFS= read -r line; do
                      case "${'$'}line" in
                        GETEVENT_EXIT=*|*'160批上限'*) echo "${'$'}line" ;;
                      esac
                    done < "${'$'}base.out"
                    if [ "${'$'}n" -eq 0 ]; then
                      echo '--getevent 前12行（用于判断无事件或命令失败）--'
                      m=0
                      while IFS= read -r line; do
                        echo "${'$'}line"
                        m=${'$'}((m + 1))
                        [ "${'$'}m" -ge 12 ] && break
                      done < "${'$'}base.out"
                    fi
                    if [ "${'$'}key_loop_reaped" = true ]; then rm -f "${'$'}base.out" "${'$'}base.pid" "${'$'}stop"; fi
                    echo ROOT_KEY_CAPTURE_END
                    ${HsaeKeyDiagnostics.rootCaptureFinishShell()}
                """.trimIndent()
    }
    private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"
}

