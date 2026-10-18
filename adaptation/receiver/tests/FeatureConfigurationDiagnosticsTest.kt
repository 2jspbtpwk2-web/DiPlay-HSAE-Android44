package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class FeatureConfigurationDiagnosticsTest {
    @Test fun configuredEndpointDoesNotClaimAnActualAdvertisementOrCapture() {
        val settings=ReceiverSettings(microphone=false)
        val text=FeatureConfigurationDiagnostics.describe(settings,settings,null)
        assertTrue(text.contains("本轮CarPlay输入端点配置=true"))
        assertTrue(text.contains("本轮采集模式capture=false syntheticSilence=true"))
        assertTrue(text.contains("实际通告/请求见info/SETUP证据"))
        assertFalse(text.contains("输入端点通告=true"))
    }
    @Test fun noSessionIsNotAnInferredFailure() {
        assertTrue(FeatureConfigurationDiagnostics.describe(ReceiverSettings(),null,null).contains("未连接时不推断"))
    }
    @Test fun frozenMicrophoneDisabledRemainsVisibleAfterPreferenceChange() {
        val current=ReceiverSettings(microphone=true)
        val s=FeatureConfigurationDiagnostics.describe(current,current.copy(microphone=false),true)
        assertTrue(s.contains("microphone=false")); assertTrue(s.contains("mic或GPS开关与冻结值不同=true"))
        assertTrue(s.contains("关闭时零数据不代表硬件坏"))
    }
    @Test fun gpsRequestedDoesNotInventAnAdvertisement() {
        val s=FeatureConfigurationDiagnostics.describe(ReceiverSettings(),ReceiverSettings(),null)
        assertTrue(s.contains("GPS能力通告=尚未确定"))
    }
    @Test fun identicalSettingsDoNotDemandAnUnneededChange() {
        val settings=ReceiverSettings()
        assertTrue(FeatureConfigurationDiagnostics.describe(settings,settings,false).contains("mic或GPS开关与冻结值不同=false"))
    }
}
