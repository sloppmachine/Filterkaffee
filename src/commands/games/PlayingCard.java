package commands.games;

import java.util.List;

public class PlayingCard {
    public static final String back = "🂠";
    private static final String allCardCharacters = "🂡🂢🂣🂤🂥🂦🂧🂨🂩🂪🂫🂭🂮🂱🂲🂳🂴🂵🂶🂷🂸🂹🂺🂻🂽🂾🃁🃂🃃🃄🃅🃆🃇🃈🃉🃊🃋🃍🃎🃑🃒🃓🃔🃕🃖🃗🃘🃙🃚🃛🃝🃞";
    public final Suit SUIT;
    public final int FACE_VALUE;

    public static enum Suit {
        HEART(0),
        DIAMOND(1),
        CLUB(2),
        SPADE(3);

        public final int SUIT_NUMBER;
        public static final Suit[] ALL_SUITS = new Suit[] {HEART, DIAMOND, CLUB, SPADE};

        private Suit(int suitNumber) {
            this.SUIT_NUMBER = suitNumber;
        }
    }

    public PlayingCard(Suit suit, int faceValue) throws IllegalArgumentException {
        if (faceValue >= 1 && faceValue <= 13) {
            this.SUIT = suit;
            this.FACE_VALUE = faceValue - 1;
        } else {
            throw new IllegalArgumentException("Card values must be from 1 to 12");
        }
    }

    // card symbols like 🂧 are represented with two character values because of how unicode representation works
    public String getCharacter() {
        int startingIndex = 2 * (SUIT.SUIT_NUMBER * 13 + FACE_VALUE);
        return allCardCharacters.substring(startingIndex, startingIndex + 2); // because the ranks start with 1, we need to look one further to the left
    }

    public String getFaceValueName() {
        if (FACE_VALUE == 0) {
            return "Ace";
        } else if (FACE_VALUE == 10) {
            return "Jack";
        } else if (FACE_VALUE == 11) {
            return "Queen";
        } else if (FACE_VALUE == 12) {
            return "King";
        } else {
            return String.valueOf(FACE_VALUE + 1);
        }
    }

    public static String getCharactersFromList(List<PlayingCard> list) {
        StringBuilder toReturnBuilder = new StringBuilder();
        for (PlayingCard playingCard : list) {
            toReturnBuilder.append(playingCard.getCharacter());
        }
        return toReturnBuilder.toString();
    }

    public static String getFaceValueNamesFromList(List<PlayingCard> list) {
        StringBuilder toReturnBuilder = new StringBuilder();
        for (PlayingCard playingCard : list) {
            if (toReturnBuilder.length() > 0) {
                toReturnBuilder.append(", ");
            }
            toReturnBuilder.append(playingCard.getFaceValueName());
        }
        return toReturnBuilder.toString();
    }

    public static int getBlackjackValue(List<PlayingCard> hand) {
        int directValue = 0; // all cards except aces have fixed values
        int aces = 0; // the values of aces is 11, except if that would make the total value exceed 21, in which case they have the value of 1
        for (PlayingCard playingCard : hand) {
            if (playingCard.FACE_VALUE == 0) {
                aces++;
            } else {
                if (playingCard.FACE_VALUE >= 10) {
                    // jack, queen and king give a value of 10
                    directValue += 10;
                } else {
                    directValue += playingCard.FACE_VALUE + 1; // the face value is offsetted by one
                }
            }
        }
        int value = directValue + aces; // this is the lowest the value can get
        if (value > 21 || aces == 0) {
            // nothing will save you if this happens
            return value;
        } else {
            int highestPossibleValue = value; // we will try to get the highest value possible without exceeding 21
            for (int acesCountedAsElevens = 0; acesCountedAsElevens < aces; acesCountedAsElevens++) {
                value = 11 * acesCountedAsElevens + directValue + (aces - acesCountedAsElevens);
                if (value > 21) {
                    // this means the last value is actually the highest possible one
                    return highestPossibleValue;
                } else {
                    highestPossibleValue = value;
                }
            }
            // this never executes, but with this, the debugger knows the function will return.
            return highestPossibleValue;
        }
    }
}