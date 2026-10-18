"""Source-structure gate only; this does not execute USB, Kotlin, or an iPhone session.

Checks the retry/discovery ordering that cannot be exercised without the physical USB path.
This deliberately recognizes the current small implementation instead of pretending to be a
Kotlin compiler. A structural refactor must update this gate after manual lifecycle review.
No source or device state is modified. The final APK's runtime tests remain separate gates.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


def mask_non_code(source):
    """Keep offsets/newlines while masking comments, chars, and Kotlin string templates."""
    size = len(source)

    def comment_end(start):
        if source.startswith("//", start):
            end = source.find("\n", start + 2)
            return size if end < 0 else end
        depth, pos = 1, start + 2
        while pos < size and depth:
            if source.startswith("/*", pos):
                depth, pos = depth + 1, pos + 2
            elif source.startswith("*/", pos):
                depth, pos = depth - 1, pos + 2
            else:
                pos += 1
        if depth:
            raise AssertionError("Unterminated block comment")
        return pos

    def template_end(start):
        depth, pos = 1, start
        while pos < size:
            if source.startswith("//", pos) or source.startswith("/*", pos):
                pos = comment_end(pos)
            elif source[pos] in "\"'":
                pos = quote_end(pos)
            elif source[pos] == "{":
                depth, pos = depth + 1, pos + 1
            elif source[pos] == "}":
                depth, pos = depth - 1, pos + 1
                if not depth:
                    return pos
            else:
                pos += 1
        raise AssertionError("Unterminated Kotlin string template")

    def quote_end(start):
        raw = source.startswith('"""', start)
        delimiter = '"""' if raw else source[start]
        pos = start + len(delimiter)
        while pos < size:
            if source.startswith(delimiter, pos):
                return pos + len(delimiter)
            if not raw and source[pos] == "\\":
                pos += 2
            elif delimiter != "'" and source.startswith("${", pos):
                pos = template_end(pos + 2)
            else:
                pos += 1
        raise AssertionError("Unterminated Kotlin string/character literal")

    masked = list(source)
    pos = 0
    while pos < size:
        if source.startswith("//", pos) or source.startswith("/*", pos):
            end = comment_end(pos)
        elif source[pos] in "\"'":
            end = quote_end(pos)
        else:
            pos += 1
            continue
        for index in range(pos, end):
            if masked[index] not in "\r\n":
                masked[index] = " "
        pos = end
    return "".join(masked)


def block(code, pattern, start=0, end=None):
    """Find the balanced block whose opening brace terminates pattern."""
    end = len(code) if end is None else end
    match = re.search(pattern, code[start:end], re.S)
    if match is None:
        raise AssertionError("Missing source block: " + pattern)
    opening = start + match.end() - 1
    if code[opening] != "{":
        raise AssertionError("Block pattern must end at its opening brace")
    depth = 1
    for pos in range(opening + 1, end):
        depth += (code[pos] == "{") - (code[pos] == "}")
        if depth == 0:
            return opening + 1, pos
    raise AssertionError("Unbalanced source block: " + pattern)


def compact(value):
    return re.sub(r"\s+", "", value)


def verify(service_source, gate_source):
    code, gate_code = mask_non_code(service_source), mask_non_code(gate_source)
    checked = []

    def require(condition, label):
        if not condition:
            raise AssertionError(label)
        checked.append(label)

    qs, qe = block(code, r"private\s+fun\s+queue\s*\([^)]*\)\s*\{")
    queue = code[qs:qe]
    require("if(!running.get()||selectedName!=null)null" in compact(queue),
            "discovery cannot allocate another token while selectedName is held")
    require("selectedName=device.deviceName;lifecycle.advance{clearPublished()}" in compact(queue),
            "selection and token allocation share the lifecycle mutation")

    ws, we = block(code, r"worker\.execute\s*\{", qs, qe)
    ts, te = block(code, r"\btry\s*\{", ws, we)
    require(compact(code[ts:te]) == "startStack(device,id)",
            "startStack normal return is covered by the worker try statement")
    cs, ce = block(code, r"\s*catch\s*\([^)]*\)\s*\{", te + 1, we)
    fs, fe = block(code, r"\s*finally\s*\{", ce + 1, we)
    require(re.fullmatch(r"\s*catch\s*\(\s*error\s*:\s*Throwable\s*\)\s*\{", code[te + 1:cs]) is not None,
            "worker catch immediately follows the startStack try")
    require(re.fullmatch(r"\s*finally\s*\{", code[ce + 1:fs]) is not None,
            "worker final release immediately follows the catch")
    final_release = compact(code[fs:fe])
    require(final_release == "lifecycle.mutate{if(id==generation.get())selectedName=null}",
            "worker releases selection only in its token-checked finally")
    require(compact(code[ws:we]).count("selectedName=null") == 1,
            "worker has no earlier selection release before failure reporting/backoff")
    catch = compact(code[cs:ce])
    report_pos, sleep_pos, cleanup_pos = catch.find("checkpoint("), catch.find("Thread.sleep(backoff)"), catch.rfind("cleanup(id)")
    require(0 <= report_pos < sleep_pos < cleanup_pos,
            "failure checkpoint precedes backoff and cleanup before final selection release")

    ss, se = block(code, r"private\s+fun\s+startStack\s*\([^)]*\)\s*\{")
    stack = code[ss:se]
    # startStack contains asynchronous NCM callbacks which legitimately release selection
    # after invalidation. Inspect only the balanced finally attached to its outer try;
    # searching the whole function would mistake those callback bodies for early release.
    stack_try_start, stack_try_end = block(code, r"\btry\s*\{", ss, se)
    stack_final_start, stack_final_end = block(code, r"\s*finally\s*\{", stack_try_end + 1, se)
    stack_final = code[stack_final_start:stack_final_end]
    require(re.search(r"\bselectedName\s*=", stack_final) is None,
            "startStack cannot reopen discovery from its inner finally")
    vs, ve = block(code, r"if\s*\(\s*configuration\s*==\s*null\s*\)\s*\{", ss, se)
    vendor = compact(code[vs:ve])
    require("0x52" in vendor and vendor.find("0x52") < vendor.rfind("return"),
            "vendor transition retains its normal return through the worker final release")
    require(re.fullmatch(r"\s*finally\s*\{", code[stack_try_end + 1:stack_final_start]) is not None
            and "cleanup(id)" in compact(stack_final)
            and not code[stack_final_end + 1:se].strip(),
            "startStack retains resource cleanup on both normal return and exception")

    ds, de = block(code, r"UsbManager\.ACTION_USB_DEVICE_DETACHED\s*->\s*\{")
    detach = compact(code[ds:de])
    require("lifecycle.advance{clearPublished()};selectedName=null;true" in detach
            and "cleanupAsync(oldGeneration)" in detach
            and not any(wait in detach for wait in ("Thread.sleep(", "worker.execute{", "awaitCleanups(")),
            "physical detach invalidates and releases selection without awaiting retry backoff")

    rs, re_ = block(gate_code, r"fun\s+retire\s*\([^{]*?\)\s*:\s*Boolean\s*=\s*synchronized\s*\(lock\)\s*\{")
    retired = compact(gate_code[rs:re_])
    require("retiredGeneration=expected" in retired and "clearPublished()" in retired
            and not re.search(r"generation\.(?:incrementAndGet|set|addAndGet|compareAndSet)\(", retired),
            "retirement preserves the failure diagnostic token")
    return checked


def main():
    receiver = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=receiver / "src/local/airuize/receiver/LegacyWiredService.kt")
    parser.add_argument("--gate", type=Path, default=receiver / "src/local/airuize/receiver/AttemptLifecycleGate.kt")
    args = parser.parse_args()
    service_bytes, gate_bytes = args.source.read_bytes(), args.gate.read_bytes()
    checked = verify(service_bytes.decode("utf-8-sig"), gate_bytes.decode("utf-8-sig"))
    print(json.dumps({
        "result": "PASS", "scope": "source_structure_only", "runtime_connection_proven": False,
        "source": str(args.source.resolve()), "source_sha256": hashlib.sha256(service_bytes).hexdigest(),
        "gate": str(args.gate.resolve()), "gate_sha256": hashlib.sha256(gate_bytes).hexdigest(),
        "checks": checked,
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, UnicodeError) as error:
        print(json.dumps({"result": "FAIL", "scope": "source_structure_only", "error": str(error)}, ensure_ascii=False))
        sys.exit(1)
