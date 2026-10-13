package com.kiktor.v2whitelist.core.domain.failover

import com.kiktor.v2whitelist.core.model.ProfileItem
import com.kiktor.v2whitelist.core.model.logger.GeekModeLogger

/**
 * Состояние физического сетевого подключения устройства.
 */
enum class PhysicalNetworkState {
    NO_INTERNET,      // Физического интернета нет совсем (лифт, метро, авиарежим)
    WHITELIST_ONLY,   // Доступны только отечественные ресурсы (режим белых списков РФ)
    FULL_INTERNET     // Полноценный интернет (доступны и зарубежные, и отечественные узлы)
}

/**
 * Детектор аномалий и затыкания трафика (Stall Detection).
 * Если входящий трафик падает ниже порога при активном исходящем на протяжении нескольких секунд,
 * фиксируется затык туннеля.
 */
class TrafficStallDetector(
    val minUplinkBytes: Double = 1500.0,      // 1.5 KB/s порог исходящей активности
    val thresholdDownBytes: Double = 3072.0,  // 3.0 KB/s порог отсутствия входящих данных
    val requiredStallSeconds: Int = 3         // Необходимая длительность аномалии в секундах
) {
    var consecutiveStallSeconds = 0
        private set

    /**
     * Обрабатывает секундный сэмпл скорости трафика.
     * @return true, если затык зафиксирован и требует экспресс-проверки туннеля.
     */
    fun onTrafficSample(proxyUpRate: Double, proxyDownRate: Double): Boolean {
        if (proxyUpRate >= minUplinkBytes && proxyDownRate < thresholdDownBytes) {
            consecutiveStallSeconds++
            GeekModeLogger.log(
                "Failover",
                "TrafficStallDetector: Potential stall #$consecutiveStallSeconds (Up: ${proxyUpRate.toInt()} B/s, Down: ${proxyDownRate.toInt()} B/s)"
            )
            if (consecutiveStallSeconds >= requiredStallSeconds) {
                consecutiveStallSeconds = 0
                return true
            }
        } else {
            consecutiveStallSeconds = 0
        }
        return false
    }

    fun reset() {
        consecutiveStallSeconds = 0
    }
}

/**
 * Селектор переключения на резервный сервер при сбое текущего.
 */
object FailoverServerSelector {

    /**
     * Выбирает следующий сервер:
     * 1. Приоритет VIP Cache (проверенные быстрые серверы).
     * 2. Следующий по кругу (Round-Robin) из отфильтрованного списка кандидатов.
     */
    fun selectNextServer(
        currentGuid: String?,
        candidates: List<Pair<String, ProfileItem>>,
        vipGuids: List<String> = emptyList()
    ): Pair<String, ProfileItem>? {
        if (candidates.isEmpty()) return null

        val candidateMap = candidates.toMap()

        // 1. Приоритет VIP Cache
        for (vipGuid in vipGuids) {
            if (vipGuid == currentGuid) continue
            val profile = candidateMap[vipGuid]
            if (profile != null) {
                GeekModeLogger.log("Failover", "Selected next server from VIP cache: ${profile.remarks} ($vipGuid)")
                return vipGuid to profile
            }
        }

        // 2. Следующий по кругу из списка кандидатов
        if (candidates.size == 1 && candidates[0].first == currentGuid) {
            return null // Нет других альтернативных серверов
        }

        val currentIndex = if (currentGuid != null) {
            candidates.indexOfFirst { it.first == currentGuid }
        } else {
            -1
        }

        val nextIndex = if (currentIndex != -1) {
            (currentIndex + 1) % candidates.size
        } else {
            0
        }

        val selected = candidates[nextIndex]
        GeekModeLogger.log("Failover", "Selected next server from candidate list: ${selected.second.remarks} (${selected.first})")
        return selected
    }

    /**
     * Определяет, нужно ли выполнять переключение при текущем состоянии физической сети.
     * Если интернет отсутствует на уровне железа (лифт/метро), переключение отменяется.
     */
    fun shouldProceedWithFailover(physicalState: PhysicalNetworkState): Boolean {
        return physicalState != PhysicalNetworkState.NO_INTERNET
    }
}
