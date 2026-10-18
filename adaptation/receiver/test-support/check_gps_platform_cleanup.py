"""Read Android's actual registration table after both diagnostic workers finish.

Run before force-stop/cold-start so process termination cannot hide a missed unregister.
Only the count is emitted; the location dump itself is never saved or printed.
"""
import subprocess
import os

command = [os.environ.get('ADB', 'adb'), '-s', os.environ.get('ANDROID_SERIAL', 'emulator-5556'), 'shell']
state = subprocess.run(command + ['dumpsys', 'location'], check=True, stdout=subprocess.PIPE).stdout.decode('utf-8', errors='replace')
assert 'Records by Provider:' in state, 'Android location registration table is unavailable'
owned = [line for line in state.splitlines() if 'UpdateRecord[' in line and 'local.airuize.diplaylegacy' in line]
assert not owned, 'diagnostic left an application location listener registered'
pid = subprocess.run(command + ['ps'], check=True, stdout=subprocess.PIPE).stdout.decode('utf-8', errors='replace')
assert any(line.rstrip().endswith('local.airuize.diplaylegacy') for line in pid.splitlines()), 'application process must still be alive before this check'
print('PASS GpsPlatformCleanup applicationAlive=true registeredLocationRecords=0 forceStopBeforeCheck=false coordinates=saved-never')
