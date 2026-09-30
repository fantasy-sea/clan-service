package com.fantasysea.clanservice.websocket

import com.fantasysea.clanservice.binding.repository.DeviceBindingRepository
import com.fantasysea.clanservice.common.cleanup.TerminalTransitionService
import com.fantasysea.clanservice.device.repository.DeviceRepository
import com.fantasysea.clanservice.pairing.entity.PairingRequestStatus
import com.fantasysea.clanservice.pairing.repository.PairingRequestRepository
import com.fantasysea.clanservice.session.repository.RemoteSessionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ScheduledFuture
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.scheduling.TaskScheduler
import org.springframework.transaction.support.TransactionTemplate

class PresenceServiceTest {
    @Test
    fun `reconnect remains silent when grace callback observes the newly registered connection`() {
        val fixture = fixture()
        fixture.service.disconnected(DEVICE_ID)
        `when`(fixture.gateway.isReachable(DEVICE_ID)).thenReturn(true)

        fixture.tasks.value.run()
        fixture.service.connected(DEVICE_ID)

        verify(fixture.gateway, never()).send(
            any(String::class.java) ?: "",
            any(WsOutboundMessage::class.java) ?: WsOutboundMessage("unused", timestamp = 0),
        )
    }

    @Test
    fun `cancelled stale callback cannot consume a newer offline grace task`() {
        val fixture = fixture()
        fixture.service.disconnected(DEVICE_ID)
        fixture.service.connected(DEVICE_ID)
        fixture.service.disconnected(DEVICE_ID)
        `when`(fixture.gateway.isReachable(DEVICE_ID)).thenReturn(false)

        fixture.tasks.allValues[0].run()
        fixture.tasks.allValues[1].run()

        verify(fixture.pairings, times(1)).findByStatusAndDevice(PairingRequestStatus.PENDING, DEVICE_ID)
    }

    private fun fixture(): Fixture {
        val scheduler = mock(TaskScheduler::class.java)
        val tasks = ArgumentCaptor.forClass(Runnable::class.java)
        val firstFuture = mock(ScheduledFuture::class.java)
        val secondFuture = mock(ScheduledFuture::class.java)
        `when`(
            scheduler.schedule(
                tasks.capture() ?: Runnable {},
                any(Instant::class.java) ?: NOW,
            ),
        ).thenReturn(firstFuture as ScheduledFuture<Any>, secondFuture as ScheduledFuture<Any>)
        val gateway = mock(WsEventGateway::class.java)
        val pairings = mock(PairingRequestRepository::class.java)
        val bindings = mock(DeviceBindingRepository::class.java)
        val sessions = mock(RemoteSessionRepository::class.java)
        `when`(bindings.findByDeviceLowIdOrDeviceHighId(DEVICE_ID, DEVICE_ID)).thenReturn(emptyList())
        `when`(pairings.findByStatusAndDevice(PairingRequestStatus.PENDING, DEVICE_ID)).thenReturn(emptyList())
        `when`(sessions.findActiveForDevice(org.mockito.ArgumentMatchers.eq(DEVICE_ID) ?: DEVICE_ID, anyCollection())).thenReturn(emptyList())
        val service = PresenceService(
            mock(DeviceRepository::class.java), bindings, pairings, sessions, gateway, scheduler,
            mock(TransactionTemplate::class.java), CLOCK, mock(TerminalTransitionService::class.java),
        )
        return Fixture(service, gateway, pairings, tasks)
    }

    @Suppress("UNCHECKED_CAST")
    private fun anyCollection() = any(Collection::class.java) as Collection<com.fantasysea.clanservice.session.entity.RemoteSessionStatus>? ?: emptyList()

    private data class Fixture(
        val service: PresenceService,
        val gateway: WsEventGateway,
        val pairings: PairingRequestRepository,
        val tasks: ArgumentCaptor<Runnable>,
    )

    companion object {
        const val DEVICE_ID = "dev_1"
        val NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }
}
