package de.blocklog;

/**
 * Ein Eintrag im Protokoll.
 * extra: bei PUT/TAKE der Container-Typ (z. B. CHEST), bei SIGN die Seite und der Text.
 */
record Entry(long time, String player, LogAction action, String world,
             int x, int y, int z, String material, int amount, String extra) {
}
