package su.kidoz.kaillera.service;

import java.util.Collection;

import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.InfoMessageEvent;

/**
 * Service for sending announcements to users and games.
 */
public class AnnouncementService {

    /**
     * Sends an announcement to all logged-in users and optionally to all active
     * games.
     *
     * @param announcement
     *            the announcement message to send
     * @param gamesAlso
     *            whether to also send to active games
     * @param users
     *            all users on the server
     * @param games
     *            all active games (may be null if gamesAlso is false)
     */
    public void announce(final String announcement, final boolean gamesAlso,
            final Collection<? extends KailleraUser> users,
            final Collection<? extends KailleraGame> games) {
        // Send to all logged-in users
        for (final KailleraUser user : users) {
            if (user.isLoggedIn()) {
                user.addEvent(new InfoMessageEvent(user, announcement));
            }
        }

        // Optionally send to all active games
        if (gamesAlso && games != null) {
            for (final KailleraGame game : games) {
                game.announce(announcement);
            }
        }
    }

    /**
     * Sends a private announcement to a single user.
     *
     * @param user
     *            the user to send the announcement to
     * @param announcement
     *            the announcement message
     */
    public void announceToUser(final KailleraUser user, final String announcement) {
        if (user.isLoggedIn()) {
            user.addEvent(new InfoMessageEvent(user, announcement));
        }
    }
}
