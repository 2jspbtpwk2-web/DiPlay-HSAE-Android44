package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class SteeringLearningPolicyTest {
    @Test fun idleOrOnePressDoesNotClaimAMapping() {
        val p = SteeringLearningPolicy()
        assertNull(p.snapshot(0).mapping)
        p.start(1000); val one = p.accept(4, 1100)
        assertEquals(SteeringLearningPolicy.Stage.NEXT, one.stage); assertEquals(1, one.count); assertNull(one.mapping)
    }
    @Test fun twoConsistentPressesPerDirectionProduceTheKnownMapping() {
        val p = SteeringLearningPolicy(); p.start(1000)
        p.accept(4, 1100); val previous = p.accept(4, 1700)
        assertEquals(SteeringLearningPolicy.Stage.PREVIOUS, previous.stage); assertEquals(0, previous.count)
        p.accept(5, 2300); val ready = p.accept(5, 2900)
        assertEquals(SteeringLearningPolicy.Stage.READY, ready.stage)
        assertEquals(4, ready.mapping?.translate(4)); assertEquals(5, ready.mapping?.translate(5))
    }
    @Test fun reversedPhysicalLabelsAreMappedOnlyAfterBothStagesAgree() {
        val p = SteeringLearningPolicy(); p.start(1000)
        p.accept(5, 1100); p.accept(5, 1700); p.accept(4, 2300)
        val mapping = p.accept(4, 2900).mapping!!
        assertEquals(4, mapping.translate(5)); assertEquals(5, mapping.translate(4)); assertNull(mapping.translate(7))
    }
    @Test fun inconsistentRepeatsFailWithoutSavingAPartialMap() {
        val p = SteeringLearningPolicy(); p.start(1000); p.accept(4, 1100)
        val failed = p.accept(5, 1700)
        assertEquals(SteeringLearningPolicy.Stage.FAILED, failed.stage); assertNull(failed.mapping)
    }
    @Test fun bothDirectionsCannotUseTheSameRawKey() {
        val p = SteeringLearningPolicy(); p.start(1000); p.accept(4, 1100); p.accept(4, 1700)
        assertEquals(SteeringLearningPolicy.Stage.FAILED, p.accept(4, 2300).stage)
    }
    @Test fun unknownOrRapidDuplicateEventsDoNotAdvanceTheWizard() {
        val p = SteeringLearningPolicy(); p.start(1000)
        assertEquals(0, p.accept(99, 1100).count)
        p.accept(4, 1200)
        assertEquals(1, p.accept(4, 1300).count)
        assertEquals(SteeringLearningPolicy.Stage.PREVIOUS, p.accept(4, 1800).stage)
    }
    @Test fun eachStageHasItsOwnBoundedDeadline() {
        val p = SteeringLearningPolicy(5000); p.start(1000)
        p.accept(4, 5000); val previous = p.accept(4, 5500)
        assertEquals(5000L, previous.remainingMillis)
        assertEquals(SteeringLearningPolicy.Stage.PREVIOUS, p.tick(10499).stage)
        assertEquals(SteeringLearningPolicy.Stage.FAILED, p.tick(10500).stage)
    }
    @Test fun eventAtOrAfterTimeoutCannotResurrectLearning() {
        val p = SteeringLearningPolicy(5000); p.start(1000)
        assertEquals(SteeringLearningPolicy.Stage.FAILED, p.accept(4, 6000).stage)
        assertNull(p.accept(5, 7000).mapping)
    }
    @Test fun cancelAndRestartDiscardAllPartialEvidence() {
        val p = SteeringLearningPolicy(); p.start(1000); p.accept(4, 1100)
        assertEquals(SteeringLearningPolicy.Stage.CANCELLED, p.cancel(1200).stage)
        assertNull(p.accept(4, 1800).mapping)
        val next = p.start(2000); assertEquals(0, next.count); assertNull(next.mapping)
    }
    @Test fun invalidStoredMappingsAreRejected() {
        for (mapping in listOf(SteeringLearningPolicy.Mapping(4, 4), SteeringLearningPolicy.Mapping(0, 5), SteeringLearningPolicy.Mapping(4, 99))) {
            assertFalse(mapping.valid()); assertNull(mapping.translate(4))
        }
    }
    @Test fun learnedMappingIsBoundToTheExactDeviceAndFirmwareIdentity() {
        assertNotNull(SteeringLearningPolicy.storedMapping("device-firmware-A", "device-firmware-A", 5, 4))
        assertNull(SteeringLearningPolicy.storedMapping("device-firmware-B", "device-firmware-A", 5, 4))
        assertNull(SteeringLearningPolicy.storedMapping("device-firmware-A", null, 5, 4))
        assertNull(SteeringLearningPolicy.storedMapping("", "", 5, 4))
        assertNull(SteeringLearningPolicy.storedMapping("device-firmware-A", "device-firmware-A", 4, 4))
    }
    @Test fun onlyTheExistingShortPressPolicyCanGenerateLearningObservations() {
        val owner = Any(); val pairs = HsaeMediaKeyPolicy().apply { reset(owner, 1000) }
        val p = SteeringLearningPolicy(); p.start(1000)
        fun event(kind: HsaeMediaKeyParser.Kind, time: Long) = HsaeMediaKeyParser.Event(kind, time, "$kind:$time")
        assertNull(pairs.accept(event(HsaeMediaKeyParser.Kind.NEXT_DOWN, 1100), owner, 1100, 1100, true, true, false))
        assertNull(pairs.accept(event(HsaeMediaKeyParser.Kind.NEXT_LONG, 1200), owner, 1200, 1200, true, true, false))
        assertNull(pairs.accept(event(HsaeMediaKeyParser.Kind.NEXT_UP, 1300), owner, 1300, 1300, true, true, false))
        assertEquals(0, p.snapshot(1300).count)
        pairs.accept(event(HsaeMediaKeyParser.Kind.NEXT_DOWN, 2000), owner, 2000, 2000, true, true, false)
        val raw = pairs.accept(event(HsaeMediaKeyParser.Kind.NEXT_UP, 2200), owner, 2200, 2200, true, true, false)
        assertEquals(1, p.accept(raw!!, 2200).count)
        assertNull(pairs.accept(event(HsaeMediaKeyParser.Kind.NEXT_UP, 2200), owner, 2200, 2300, true, true, false))
    }
    @Test fun batchedPressesUsePhysicalSpacingWithMonotonicDeadline() {
        val p=SteeringLearningPolicy();p.start(1000)
        assertEquals(1,p.acceptObserved(4,3000,1700).count)
        assertEquals(SteeringLearningPolicy.Stage.PREVIOUS,p.acceptObserved(4,3000,900).stage)
        assertEquals(25000L,p.snapshot(3000).remainingMillis)
        assertEquals(1,p.acceptObserved(5,4500,1000).count)
        assertEquals(SteeringLearningPolicy.Stage.READY,p.acceptObserved(5,4500,150).stage)
    }
    @Test fun staleAndFutureObservationAgesNeverAdvanceLearning() {
        val p=SteeringLearningPolicy();p.start(1000)
        assertEquals(0,p.acceptObserved(4,3000,1801).count)
        assertEquals(0,p.acceptObserved(4,3000,-1).count)
        assertEquals(1,p.acceptObserved(4,3000,300).count)
        assertEquals(1,p.acceptObserved(4,3000,400).count)
    }
    @Test fun physicalPressBeforeDeadlineCanArriveWithinFiniteDeliveryGrace() {
        val p=SteeringLearningPolicy(5000);p.start(1000)
        assertEquals(1,p.acceptObserved(4,5000,0).count)
        assertEquals(SteeringLearningPolicy.Stage.NEXT,p.tickDelivery(6000).stage)
        val accepted=p.acceptObserved(4,6500,600)
        assertEquals(SteeringLearningPolicy.Stage.PREVIOUS,accepted.stage)
        assertEquals(5000L,accepted.remainingMillis)
    }
    @Test fun deliveryGraceCannotAdmitAPhysicalPressAfterDeadlineOrExtendForever() {
        val p=SteeringLearningPolicy(5000);p.start(1000)
        p.acceptObserved(4,5000,0)
        assertEquals(1,p.acceptObserved(4,6500,500).count)
        assertEquals(1,p.acceptObserved(4,7000,0).count)
        assertEquals(SteeringLearningPolicy.Stage.FAILED,p.tickDelivery(7800).stage)
        assertNull(p.acceptObserved(4,7801,1800).mapping)
    }
    @Test fun previousStagePhysicalEventCannotCountInNewStage() {
        val p=SteeringLearningPolicy();p.start(1000)
        p.acceptObserved(4,2000,0);p.acceptObserved(4,2500,0)
        assertEquals(SteeringLearningPolicy.Stage.PREVIOUS,p.snapshot(2500).stage)
        assertEquals(0,p.acceptObserved(5,2600,150).count)
        assertEquals(1,p.acceptObserved(5,3000,100).count)
    }
}
