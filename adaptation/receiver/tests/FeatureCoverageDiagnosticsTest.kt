package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class FeatureCoverageDiagnosticsTest {
    @Test fun disabledDoesNotClaimFailureFromNoTraffic() {
        assertTrue(FeatureCoverageDiagnostics.describe(false, 0, 0, 0).contains("未启用"))
    }
    @Test fun noRequestIsUntestedRatherThanBroken() {
        assertTrue(FeatureCoverageDiagnostics.describe(true, 0, 0, 0).contains("未测试或手机未触发"))
    }
    @Test fun requestAloneDoesNotClaimDataFlow() {
        val text = FeatureCoverageDiagnostics.describe(true, 1, 0, 0)
        assertTrue(text.contains("尚未记录数据进展"))
        assertFalse(text.contains("有软件数据进展"))
    }
    @Test fun progressDoesNotClaimHardwareOutcome() {
        val text = FeatureCoverageDiagnostics.describe(true, 1, 4, 0)
        assertTrue(text.contains("有软件数据进展"))
        assertTrue(text.contains("不证明实际听感/识别或iPhone采用"))
    }
    @Test fun errorIsVisibleEvenWhenThereWasEarlierProgress() {
        val text = FeatureCoverageDiagnostics.describe(true, 2, 4, 1)
        assertTrue(text.contains("记录到错误"))
        assertTrue(text.contains("请求=2 进展=4 错误=1"))
    }
    @Test fun negativeMissingCountersNeverBecomeNegativeProgress() {
        assertTrue(FeatureCoverageDiagnostics.describe(true, -1, -1, -1).contains("未观察到请求"))
    }
}
