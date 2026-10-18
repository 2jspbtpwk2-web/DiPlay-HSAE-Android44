package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class LegacyDisplayPolicyTest {
    @Test fun freshInstallUsesTheAvailablePortraitArea() {
        assertEquals(LegacyDisplayPolicy.AUTO, LegacyDisplayPolicy.mode(null, null, null, false))
        assertEquals(LegacyDisplayPolicy.Size(720, 864), LegacyDisplayPolicy.resolve("auto", 960, 1150, 1024, 600))
    }

    @Test fun bothFormerDefaultsMigrateToAutomatic() {
        assertEquals("auto", LegacyDisplayPolicy.mode(null, 1024, 600, false))
        assertEquals("auto", LegacyDisplayPolicy.mode(null, 800, 480, true))
        assertEquals("auto", LegacyDisplayPolicy.mode(null, null, null, true))
    }

    @Test fun genuineExistingCustomDimensionsRemainManual() {
        assertEquals("manual", LegacyDisplayPolicy.mode(null, 1280, 720, false))
        assertEquals("manual", LegacyDisplayPolicy.mode(null, 960, 1080, false))
        assertEquals("manual", LegacyDisplayPolicy.mode(null, 1280, null, false))
        assertEquals("manual", LegacyDisplayPolicy.mode(null, null, 720, false))
    }

    @Test fun explicitlyChosenModesWinOverMigration() {
        assertEquals("manual", LegacyDisplayPolicy.mode("manual", 1024, 600, false))
        assertEquals("smooth", LegacyDisplayPolicy.mode("smooth", 1280, 720, false))
        assertEquals("auto", LegacyDisplayPolicy.mode("auto", 1280, 720, false))
    }

    @Test fun anUnknownModeFallsBackUsingTheExistingDimensions() {
        assertEquals("manual", LegacyDisplayPolicy.mode("bogus", 1280, 720, false))
        assertEquals("auto", LegacyDisplayPolicy.mode("bogus", null, null, false))
    }

    @Test fun smoothModeKeepsPortraitAspectWithLowerPixelCost() {
        val result = LegacyDisplayPolicy.resolve("smooth", 960, 1150, 1024, 600)
        assertEquals(LegacyDisplayPolicy.Size(640, 768), result)
        assertTrue(result.width * result.height < 720 * 864)
    }

    @Test fun clarityModeRespectsTheLegacyCodecLongEdgeLimit() {
        assertEquals(LegacyDisplayPolicy.Size(896, 1072), LegacyDisplayPolicy.resolve("clear", 960, 1150, 1024, 600))
        assertEquals(LegacyDisplayPolicy.Size(800, 1072), LegacyDisplayPolicy.resolve("clear", 960, 1280, 1024, 600))
    }

    @Test fun permanentOemBarIsExcludedFromTheAdvertisedAspect() {
        val usable = LegacyDisplayPolicy.resolve("auto", 960, 1150, 1024, 600)
        val physical = LegacyDisplayPolicy.resolve("auto", 960, 1280, 1024, 600)
        assertEquals(LegacyDisplayPolicy.Size(720, 864), usable)
        assertEquals(LegacyDisplayPolicy.Size(720, 960), physical)
        assertNotEquals(physical, usable)
    }

    @Test fun landscapeRemainsLandscapeAndUsesFullWidthWithinThePixelBudget() {
        val result = LegacyDisplayPolicy.resolve("auto", 1280, 720, 1024, 600)
        assertEquals(LegacyDisplayPolicy.Size(1072, 608), result)
        assertTrue(result.width > result.height)
    }

    @Test fun allAutomaticModesHavePositiveAlignedBoundedDimensions() {
        for (mode in listOf("auto", "smooth", "clear")) {
            for ((w, h) in listOf(960 to 1150, 960 to 1280, 1920 to 1080, 480 to 800, 1 to Int.MAX_VALUE, 0 to 0)) {
                val result = LegacyDisplayPolicy.resolve(mode, w, h, 1024, 600)
                assertTrue(result.width in 16..1072)
                assertTrue(result.height in 16..1072)
                assertEquals(0, result.width % 16)
                assertEquals(0, result.height % 16)
            }
        }
    }

    @Test fun manualModePreservesTheCustomCanvas() {
        assertEquals(LegacyDisplayPolicy.Size(1280, 720), LegacyDisplayPolicy.resolve("manual", 960, 1150, 1280, 720))
        assertEquals(LegacyDisplayPolicy.Size(720, 1280), LegacyDisplayPolicy.resolve("manual", 960, 1150, 720, 1280))
        assertEquals(LegacyDisplayPolicy.Size(640, 1920), LegacyDisplayPolicy.resolve("manual", 960, 1150, -1, Int.MAX_VALUE))
    }

    @Test fun portraitSurfaceFitsWithoutStretchingOrRotatingTheTouchArea() {
        assertEquals(LegacyDisplayPolicy.Size(958, 1149), LegacyDisplayPolicy.fit(960, 1150, 720, 864))
        assertEquals(LegacyDisplayPolicy.Size(960, 562), LegacyDisplayPolicy.fit(960, 1150, 1024, 600))
    }

    @Test fun landscapeSurfaceUsesTheSameAspectFitRule() {
        assertEquals(LegacyDisplayPolicy.Size(1280, 750), LegacyDisplayPolicy.fit(1280, 800, 1024, 600))
        assertEquals(LegacyDisplayPolicy.Size(0, 0), LegacyDisplayPolicy.fit(0, 800, 1024, 600))
    }

    @Test fun fitUsesWideArithmeticForLargeMeasurements() {
        assertEquals(LegacyDisplayPolicy.Size(Int.MAX_VALUE, Int.MAX_VALUE),
            LegacyDisplayPolicy.fit(Int.MAX_VALUE, Int.MAX_VALUE, 1920, 1920))
    }
}
