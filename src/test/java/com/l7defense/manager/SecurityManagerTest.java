package com.l7defense.manager;

import com.l7defense.module.DefenseModule;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SecurityManagerTest {
    static SecurityManager manager(int minDelay) {
        return new SecurityManager(2, 30, 15, 10, 10, 100, 10, 50, 60, 3, minDelay, 15);
    }

    @Test void concurrentVerificationIsConsumedExactlyOnce() throws Exception {
        var manager = manager(0);
        UUID id = UUID.randomUUID();
        manager.startVerification("192.0.2.1", id);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<SecurityManager.VerificationResult>> calls = new ArrayList<>();
            for (int i = 0; i < 128; i++) calls.add(() -> manager.checkVerification("192.0.2.1", id));
            long passed = 0;
            for (var result : pool.invokeAll(calls)) if (result.get() == SecurityManager.VerificationResult.PASSED) passed++;
            assertEquals(1, passed);
            assertTrue(manager.isVerified(id));
        } finally { pool.shutdownNow(); }
    }

    @Test void earlyRetriesDoNotResetOrPassChallenge() {
        var manager = manager(3);
        UUID id = UUID.randomUUID();
        manager.startVerification("192.0.2.1", id);
        assertEquals(SecurityManager.VerificationResult.WAITING, manager.checkVerification("192.0.2.1", id));
        assertEquals(SecurityManager.VerificationResult.WAITING, manager.checkVerification("192.0.2.1", id));
        assertFalse(manager.isVerified(id));
    }

    @Test void sharedIpCannotConsumeAnotherAccountsVerification() {
        var manager = manager(0);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        manager.startVerification("192.0.2.1", first);
        assertEquals(SecurityManager.VerificationResult.NEW, manager.checkVerification("192.0.2.1", second));
        assertEquals(SecurityManager.VerificationResult.NEW, manager.checkVerification("192.0.2.2", first));
        assertEquals(SecurityManager.VerificationResult.PASSED, manager.checkVerification("192.0.2.1", first));
        assertFalse(manager.isVerified(second));
    }

    @Test void ordinaryReconnectFloodStillBlocks() {
        var manager = manager(0);
        assertFalse(manager.recordAndCheckConnection("192.0.2.1"));
        assertFalse(manager.recordAndCheckConnection("192.0.2.1"));
        assertTrue(manager.recordAndCheckConnection("192.0.2.1"));
        assertTrue(manager.isBlocked("192.0.2.1"));
        assertTrue(manager.unblockIp("192.0.2.1"));
        assertFalse(manager.recordAndCheckConnection("192.0.2.1"));
    }

    @Test void captchaStateIsPerAccountAndCannotBeReplacedByAnotherLogin() {
        var states = new CaptchaStates();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        states.flag(first, DefenseModule.GUI_CAPTCHA);
        states.flag(second, DefenseModule.MAP_CAPTCHA);
        states.join(first);
        states.flag(first, DefenseModule.ENTITY_CAPTCHA);
        assertTrue(states.needs(first, DefenseModule.GUI_CAPTCHA));
        assertFalse(states.pass(first, DefenseModule.MAP_CAPTCHA));
        assertTrue(states.pass(first, DefenseModule.GUI_CAPTCHA));
        assertTrue(states.needs(second, DefenseModule.MAP_CAPTCHA));
        states.discardPending(second);
        assertNull(states.get(second));
    }

    @Test void quittingAndShutdownClearActiveChallenges() {
        var manager = manager(0);
        UUID id = UUID.randomUUID();
        manager.getCaptchas().start(id, DefenseModule.MAP_CAPTCHA);
        manager.getCaptchas().quit(id);
        assertFalse(manager.needsMapCaptcha(id));
        manager.getCaptchas().start(id, DefenseModule.MAP_CAPTCHA);
        manager.shutdown();
        assertFalse(manager.needsMapCaptcha(id));
        manager.blockIp("192.0.2.1", "stale async task");
        assertFalse(manager.isBlocked("192.0.2.1"));
    }

    @Test void redisChangesDoNotEchoAndLocalChangesPublishOnce() {
        var manager = manager(0);
        var redis = org.mockito.Mockito.mock(com.l7defense.util.RedisManager.class);
        manager.setRedisManager(redis);
        manager.blockIp("192.0.2.1", "local");
        manager.blockIp("192.0.2.1", "duplicate");
        manager.receiveRemoteBlock("192.0.2.2");
        manager.receiveRemoteUnblock("192.0.2.2");
        manager.unblockIp("192.0.2.1");
        org.mockito.Mockito.verify(redis).publishBlock("192.0.2.1");
        org.mockito.Mockito.verify(redis).publishUnblock("192.0.2.1");
        org.mockito.Mockito.verifyNoMoreInteractions(redis);
    }
}
