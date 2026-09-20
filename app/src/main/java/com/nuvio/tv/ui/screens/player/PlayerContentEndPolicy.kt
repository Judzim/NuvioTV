package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval

/*
 * Segmenty, ktore oznacuju koniec obsahu: outro serialu (aj jeho anime varianty) a zaverecne titulky
 * filmu. Mobile ich ma v `features/player/skip/SkipModels.kt` ako `ContentEndSegmentTypes`; TV ma
 * `outro` v `PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES`, takze filmovy typ sa pridava az tu.
 */
internal val ContentEndSegmentTypes: Set<String> =
    PlayerNextEpisodeRules.OUTRO_SEGMENT_TYPES + setOf("movie-credits")

/**
 * Kde obsah naozaj konci, v percentach svojej dlzky, podla markeru z IntroDB.
 *
 * Cita sa len marker, nikdy nastavenie skippovania: prehratie, ktore doslo na titulky, je skoncene aj
 * pre tracker. Pouzije sa najskorsi marker typu konca obsahu, teda tam, kde sa obsah konci, a nie tam,
 * kde konci posledna zaverecna scena.
 *
 * Neplatne hodnoty sa odmietnu: nezmyselna dlzka, start mimo videa alebo start, ktory nie je kladne
 * cislo, vratia `null`. To znamena, ze rozhoduje prah, ktory si nastavil pouzivatel, rovnako ako pre
 * titul, ktory IntroDB nepozna.
 */
internal fun List<SkipInterval>.contentEndPercent(durationMs: Long): Double? {
    if (durationMs <= 0L) return null
    val creditsStartSeconds = filter { interval -> interval.type in ContentEndSegmentTypes }
        .minOfOrNull { interval -> interval.startTime }
        ?: return null
    if (!creditsStartSeconds.isFinite() || creditsStartSeconds <= 0.0) return null
    val creditsStartMs = creditsStartSeconds * 1_000.0
    if (creditsStartMs >= durationMs) return null
    return creditsStartMs / durationMs.toDouble() * 100.0
}

/**
 * Ten isty marker pre prehratie, ktore je prave na obrazovke.
 *
 * Dlzka sa berie z prehravaca a ked uz nie je k dispozicii, z poslednej znacej dlzky, rovnako ako to
 * robi `currentPlaybackProgressPercent`. Stop sa totiz odosiela aj pri odchode z obrazovky, kedy uz
 * prehravac nemusi odpovedat, a marker pritom patri tomuto prehratiu, nie poziadavke.
 */
internal fun PlayerRuntimeController.currentContentEndPercent(): Double? {
    val durationMs = currentPlaybackDurationMs().takeIf { it > 0L } ?: lastKnownDuration
    return skipIntervals.contentEndPercent(durationMs)
}
