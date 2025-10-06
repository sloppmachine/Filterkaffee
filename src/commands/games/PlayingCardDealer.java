package commands.games;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class PlayingCardDealer {
    public final int DECKS;
    public final int CARDS;
    private List<PlayingCard> order = new LinkedList<PlayingCard>();

    public PlayingCardDealer(int decks) {
        this.DECKS = decks;
        this.CARDS = decks * 13 * 4;
        refillAndShuffle();
    }

    public void refillAndShuffle() {
        order = new LinkedList<PlayingCard>();
        for (int currentDeck = 0; currentDeck < DECKS; currentDeck++) {
            for (PlayingCard.Suit suit : PlayingCard.Suit.ALL_SUITS) {
                for (int rank = 1; rank <= 13; rank++) {
                    order.add(new PlayingCard(suit, rank));
                }
            }
        }
        Collections.shuffle(order);
        return;
    }

    public boolean hasNext() {
        return !order.isEmpty();
    }

    public PlayingCard dealCard() {
        if (order.isEmpty()) {
            return null;
        } else {
            return order.removeFirst();
        }
    }

    public int cardsLeft() {
        return order.size();
    }
}