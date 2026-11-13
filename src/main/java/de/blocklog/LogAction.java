package de.blocklog;

/** Art eines Protokoll-Eintrags. Die Zahl wird in der Datenbank gespeichert. */
enum LogAction {
    PLACE(0),
    BREAK(1),
    PUT(2),
    TAKE(3),
    SIGN(4);

    final int id;

    LogAction(int id) {
        this.id = id;
    }

    static LogAction fromId(int id) {
        for (LogAction action : values()) {
            if (action.id == id) {
                return action;
            }
        }
        return PLACE;
    }
}
