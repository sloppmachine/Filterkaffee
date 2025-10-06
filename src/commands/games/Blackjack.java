package commands.games;

import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import unrelibrary.discordobjects.components.TextDisplay;
import unrelibrary.discordobjects.components.TextInput;
import unrelibrary.discordobjects.GuildMember;
import unrelibrary.discordobjects.components.ActionRow;
import unrelibrary.discordobjects.components.Button;
import unrelibrary.discordobjects.components.Component;
import unrelibrary.discordobjects.components.Container;
import unrelibrary.discordobjects.components.Label;
import unrelibrary.discordobjects.components.MediaGallery;
import unrelibrary.discordobjects.components.Section;
import unrelibrary.discordobjects.components.Separator;
import unrelibrary.discordobjects.components.Thumbnail;
import unrelibrary.discordobjects.components.UnfurledMediaItem;
import unrelibrary.discordobjects.interactions.ComponentInteraction;
import unrelibrary.discordobjects.interactions.Interaction;
import unrelibrary.discordobjects.interactions.ModalInteraction;
import unrelibrary.discordobjects.interactions.SlashCommandInteraction;
import unrelibrary.restapi.CustomIDListeningUpdate;
import unrelibrary.restapi.SlashCommand;

// for custom ids, use strings with lower camelcase and a space as a seperator. first, the intended game state when interacted, then the action and further info.
// for example "ready join " + id.
// mention member field is guaranteed because of slashcommand context
// mention manually removing the custom ids if called in the wrong state is a failsafe
// mention card counting works
public class Blackjack {
    private static final int MAX_PARTICIPANTS = 6;
    private static final int MIN_DECKS = 4;
    private static final int MAX_DECKS = 10;
    
    private static final Random RANDOM = new Random(System.currentTimeMillis());
    private static Map<Long, Game> idToGame = new TreeMap<Long, Game>();

    private static class Game {
        public static enum State {
            REGISTERED, // the game object has been created locally and registered in idToGame. this isn't used as of now, but this setup would allow to set up a game without directly sending its message.
            READY, // the game has an associated message in chat
            BETTING_PHASE,
            IN_GAME, // the game is running
            RESULTS, // the results of a single round are displayed
            FINISHED // the game is over
        };
        public final long ID;
        public final int DECKS;
        private List<Participant> participants = new LinkedList<Participant>(); // this stuff has to be synchronized to be thread safe
        private GuildMember host;
        private State state;
        private State lastState = null;
        public String name;
        public PlayingCardDealer playingCardDealer;
        public List<PlayingCard> dealerHand = new LinkedList<PlayingCard>();
        public Participant.HandState dealerHandState = Participant.HandState.UNFINISHED;

        public Game(long id, int decks, String name, GuildMember host) {
            this.ID = id;
            this.DECKS = decks;
            this.state = State.REGISTERED;
            this.name = name;
            this.host = host;
            playingCardDealer = new PlayingCardDealer(DECKS);
        }

        public static class Participant {
            public static enum PlayingState {
                NOT_YET_BET,
                HAS_BET, // this is the state while actually "playing"
                BANKRUPT,
                LEFT
            }
            public static enum HandState {
                UNFINISHED, // this means that the player still has to make a move
                STAND,
                BUST,
                TWENTYONE
            }
            public static final String defaultAvatar = "https://upload.wikimedia.org/wikipedia/commons/a/a6/Anonymous_emblem.svg";
            public final GuildMember MEMBER;
            public int currency;
            //public boolean hasBet;
            //public boolean bankrupt;
            public int bet;
            public List<PlayingCard> hand = new LinkedList<PlayingCard>();
            public HandState handState;
            public PlayingState playingState;
            public boolean ready = false; // ready for the next round.

            public Participant(GuildMember member) {
                this.MEMBER = member;
                currency = 1000;
                playingState = PlayingState.NOT_YET_BET;
                bet = 0;
            }

            // THIS NEEDS TO CONTAIN ALL FIELDS
            public Participant clone() {
                Participant toReturn = new Participant(MEMBER);
                return toReturn;
            }

            public int getCurrency() {
                return currency;
            }
        }

        public synchronized State getState() {
            return state;
        }

        public synchronized State getLastState() {
            return lastState;
        }

        public synchronized void setState(State state) {
            lastState = this.state;
            this.state = state;
            if (state == State.BETTING_PHASE) {
                for (Participant participant : participants) {
                    // this is the point where we declare players bankrupt
                    if (participant.currency == 0) {
                        participant.playingState = Participant.PlayingState.BANKRUPT;
                    }
                    // players who have left or are bankrupt are not effected.
                    if (participant.playingState == Participant.PlayingState.HAS_BET) {
                        participant.playingState = Participant.PlayingState.NOT_YET_BET;
                    }
                    participant.ready = false;                    
                }
                if (((double) playingCardDealer.cardsLeft() / (double) playingCardDealer.CARDS) < 0.25) {
                    playingCardDealer.refillAndShuffle();
                }
            } else if (state == State.IN_GAME) {
                resetHands();
                initialDealing();
            } else if (state == State.RESULTS) {
                finalDealing();
                evaluateRound();
            } else if (state == State.FINISHED) {
                sortParticipantsByCurrency();
            }
            return;
        }

        public synchronized void deleteGame() {
            // this just deletes its last reference
            Blackjack.idToGame.remove(ID);
            return;
        }

        public synchronized boolean addParticipant(GuildMember member) {
            if (participants.size() >= MAX_PARTICIPANTS) {
                return false;
            } else {
                participants.add(new Participant(member));
                return true;
            }
        }

        public synchronized boolean removeParticipant(long id) {
            for (int i = 0; i < participants.size(); i++) {
                if (participants.get(i).MEMBER.USER.ID == id) {
                    participants.remove(i);
                    return true;
                }
            }
            return false;
        }

        public synchronized void handOutCard(Participant participant) {
            participant.hand.add(playingCardDealer.dealCard());
            return;
        }

        public synchronized String[] participantGlobalNames() {
            String[] toReturn = new String[participants.size()];
            for (int i = 0; i < participants.size(); i++) {
                toReturn[i] = participants.get(i).MEMBER.USER.GLOBAL_NAME; // since strings are immutable anyway, we can just pass a reference
            }
            return toReturn;
        }

        public synchronized Participant getParticipantByUserID(long userID) {
            for (Participant participant : participants) {
                if (participant.MEMBER.USER.ID == userID) {
                    return participant;
                }
            }
            return null;
        }

        public synchronized String[] participantAvatars() {
            String[] toReturn = new String[participants.size()];
            for (int i = 0; i < participants.size(); i++) {
                toReturn[i] = participants.get(i).MEMBER.getVisibleAvatarURL(); // this ignores server profile pictures
            }
            return toReturn;
        }

        public synchronized boolean activePlayersExist() {
            for (Participant participant : participants) {
                // players are only declared bankrupt at the beginning of a new round, so that they are still displayed normally during the results.
                if ((participant.playingState == Participant.PlayingState.NOT_YET_BET || participant.playingState == Participant.PlayingState.HAS_BET) && participant.currency != 0) {
                    return true;
                }
            }
            return false;
        }

        public synchronized boolean everybodyHasBetted() { // returns true also if no active players exist anymore.
            for (Participant participant : participants) {
                // ignore people who left or are bankrupt
                if (participant.playingState == Participant.PlayingState.NOT_YET_BET) {
                    return false;
                }
            }
            return true;
        }

        public synchronized boolean someoneCanMakeAMove() {
            for (Participant participant : participants) {
                if (participant.handState == Participant.HandState.UNFINISHED) {
                    return true;
                }
            }
            return false;
        }

        public synchronized int participantsStillToReadyUp() {
            int toReturn = 0;
            for (Game.Participant participant : participants) {
                // every player who can ready up for the next round 
                if (participant.playingState == Game.Participant.PlayingState.HAS_BET) {
                    if (!participant.ready && participant.currency > 0) {
                        toReturn++;
                    }
                }
            }
            return toReturn;
        }

        public synchronized void resetHands() {
            dealerHand = new LinkedList<PlayingCard>();
            for (Participant participant : participants) {
                participant.hand = new LinkedList<PlayingCard>();
            }
            return;
        }

        public synchronized void initialDealing() {
            // kick out those who are bankrupt
            // deal everyone two cards
            dealerHand.add(playingCardDealer.dealCard());
            dealerHand.add(playingCardDealer.dealCard());
            for (Participant participant : participants) {
                if (participant.playingState == Participant.PlayingState.HAS_BET) {
                    participant.hand.add(playingCardDealer.dealCard());
                    participant.hand.add(playingCardDealer.dealCard());
                    // it's not possible for players to bust at this point. set everyone's state to unfinished.
                    participant.handState = Participant.HandState.UNFINISHED;
                } else {
                    participant.handState = null;
                }
                
            }
            return;
        }

        public synchronized void finalDealing() {
            // once all players are done, the dealer proceeds
            int dealerHandValue = PlayingCard.getBlackjackValue(dealerHand);
            while (dealerHandValue < 17) {
                dealerHand.add(playingCardDealer.dealCard());
                dealerHandValue = PlayingCard.getBlackjackValue(dealerHand);
                if (dealerHandValue > 21) {
                    dealerHandState = Participant.HandState.BUST;
                    return;
                } else if (dealerHandValue == 21) {
                    dealerHandState = Participant.HandState.TWENTYONE;
                    return;
                }
            }
            dealerHandState = Participant.HandState.STAND;
            return;
        }

        public synchronized void evaluateRound() {
            // see who wins and loses, and adjust bets and currency.
            for (Participant participant : participants) {
                int dealerHandValue = PlayingCard.getBlackjackValue(dealerHand);
                int participantHandValue = PlayingCard.getBlackjackValue(participant.hand);
                if (participant.playingState == Participant.PlayingState.BANKRUPT || participant.playingState == Participant.PlayingState.LEFT) {
                    continue;
                } else if (participant.handState == Participant.HandState.BUST) {
                    // every player that busts loses, regardless of the dealer.
                    participant.currency -= participant.bet;
                } else if (dealerHandState == Participant.HandState.BUST) {
                    participant.currency += participant.bet;
                } else if (dealerHandValue > participantHandValue) {
                    participant.currency -= participant.bet;
                } else if (dealerHandValue == participantHandValue) {
                    int dealerHandSize = dealerHand.size();
                    int participantHandSize = participant.hand.size();
                    if (dealerHandSize == 2 && participantHandSize == 2) {
                        // tie.
                    } else if (dealerHandSize == 2) {
                        participant.currency -= participant.bet;
                    } else if (participantHandSize == 2) {
                        participant.currency += (int) participant.bet * 1.5;
                    } else {
                        // tie.
                    }
                } else {
                    participant.currency += participant.bet;
                }
            }
            return;
        }

        public synchronized void sortParticipantsByCurrency() {
            // sort the participants by their currency, so that they can be ordered in getParticipantText
            Collections.sort(participants, Collections.reverseOrder(Comparator.comparingInt(Participant::getCurrency)));
            return;
        }

        // used to return the component response necessary to update the game's message on discord to the current state.
        public synchronized Interaction.CustomIDUpdatingResponse updateGameCustomIDUpdatingResponse(boolean newState) {
            Interaction.MessageResponse response = new Interaction.MessageResponse(7); // this means editing the original message
            response.data.flags = (int) Math.pow(2, 15);
            response.data.components = getGameComponents(this);
            // if the state changes, start listening to new custom ids
            if (newState) {
                return new Interaction.CustomIDUpdatingResponse(
                    response,
                    getGameStateChangeCustomIDListeningUpdate(this, getState(), getLastState())
                );
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    response,
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public synchronized Interaction.ModalResponse bettingModalResponse(Participant participant) {
            Interaction.ModalResponse response = new Interaction.ModalResponse();
            response.data.customID = "bettingPhase betModal " + ID;
            response.data.title = "Place your bets";
            response.data.components = getBettingModalComponents(this, participant);
            return response;
        }

        public Interaction.CustomIDUpdatingResponse readyJoinInteraction(ComponentInteraction componentInteraction) {
            GuildMember member = componentInteraction.MEMBER;
            if (state != State.READY) {
                // users can only join in the ready phase. this case shouldn't occur, but if it does because of latency or so, just ignore it and stop listening
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"ready join " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(member.USER.ID) != null) {
                // do nothing
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            } else {
                addParticipant(member);
                return updateGameCustomIDUpdatingResponse(false);
            }
        }

        public Interaction.CustomIDUpdatingResponse readyLeaveInteraction(ComponentInteraction componentInteraction) {
            if (state != State.READY) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"ready leave " + ID},
                        null,
                        null
                    )
                );
            } else if (componentInteraction.MEMBER.USER.ID == host.USER.ID) {
                // the game ends when the host leaves
                setState(State.FINISHED);
                Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                deleteGame();
                return toReturn;
            } else {
                removeParticipant(componentInteraction.MEMBER.USER.ID);
                return updateGameCustomIDUpdatingResponse(false);
            }
        }

        public Interaction.CustomIDUpdatingResponse readyStartInteraction(ComponentInteraction componentInteraction) {
            if (state != State.READY) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"ready start " + ID},
                        null,
                        null
                    )
                );
            } else if (componentInteraction.MEMBER.USER.ID == host.USER.ID) {
                setState(State.BETTING_PHASE);
                return updateGameCustomIDUpdatingResponse(true);
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        // pressing the bet button opens a modal. that modal can be used to input a number and uses a different interaction function.
        public Interaction.CustomIDUpdatingResponse bettingPhaseBetButtonInteraction(ComponentInteraction componentInteraction) {
            if (state != State.BETTING_PHASE) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"bettingPhase betButton " + ID},
                        null,
                        null
                    )
                );
            } else {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                if (participant != null) {
                    return new Interaction.CustomIDUpdatingResponse(
                        bettingModalResponse(
                            participant
                        ),
                        new CustomIDListeningUpdate(null, null, null, null)
                    );
                } else {
                    return new Interaction.CustomIDUpdatingResponse(
                        new Interaction.MessageResponse(6),
                        new CustomIDListeningUpdate(null, null, null, null)
                    );
                }
            }
        }

        public Interaction.CustomIDUpdatingResponse bettingPhaseBetModalInteraction(ModalInteraction modalInteraction) {
            if (state != State.BETTING_PHASE) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"bettingPhase betModal " + ID}, 
                        null, 
                        null
                    )
                );
            } else {
                Participant participant = getParticipantByUserID(modalInteraction.MEMBER.USER.ID);
                if (participant != null) {
                    int bettedValue = 0;
                    try {
                        bettedValue = Integer.valueOf(modalInteraction.DATA.MODAL_COMPONENTS[0].MODAL_COMPONENT_SUBMISSION.VALUE);
                    } catch (NumberFormatException numberFormatException) {
                        return new Interaction.CustomIDUpdatingResponse(
                            new Interaction.MessageResponse(6),
                            new CustomIDListeningUpdate(null, null, null, null)
                        );
                    }
                    if (bettedValue > 0 && bettedValue <= participant.currency) {
                        participant.playingState = Participant.PlayingState.HAS_BET;
                        participant.bet = bettedValue;
                    }
                    // this is the point where we switch state if everybody has betted
                    if (everybodyHasBetted()) {
                        setState(Game.State.IN_GAME);
                        return updateGameCustomIDUpdatingResponse(true);
                    } else {
                        return updateGameCustomIDUpdatingResponse(false);
                    }
                    
                } else {
                    return new Interaction.CustomIDUpdatingResponse(
                        new Interaction.MessageResponse(6),
                        new CustomIDListeningUpdate(null, null, null, null)
                    );
                }
            }
        }

        public Interaction.CustomIDUpdatingResponse bettingPhaseEndInteraction(ComponentInteraction componentInteraction) {
            if (state != State.BETTING_PHASE) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"bettingPhase end " + ID},
                        null,
                        null
                    )
                );
            } else if (componentInteraction.MEMBER.USER.ID == host.USER.ID) {
                setState(State.FINISHED);
                Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                deleteGame();
                return toReturn;
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse bettingPhaseLeaveInteraction(ComponentInteraction componentInteraction) {
            if (state != State.BETTING_PHASE) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"bettingPhase leave " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                participant.playingState = Participant.PlayingState.LEFT;
                if (!activePlayersExist()) {
                    setState(State.FINISHED);
                    Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                    deleteGame();
                    return toReturn;
                } else {
                    return updateGameCustomIDUpdatingResponse(false);
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse inGameHitInteraction(ComponentInteraction componentInteraction) {
            if (state != State.IN_GAME) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"inGame hit " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                if (participant.handState == Participant.HandState.UNFINISHED) {
                    handOutCard(participant);
                    int handValue = PlayingCard.getBlackjackValue(participant.hand);
                    if (handValue > 21) {
                        participant.handState = Game.Participant.HandState.BUST;
                    } else if (handValue == 21) {
                        participant.handState = Game.Participant.HandState.TWENTYONE;
                    }
                    if (someoneCanMakeAMove()) {
                        return updateGameCustomIDUpdatingResponse(false);
                    } else {
                        setState(State.RESULTS);
                        return updateGameCustomIDUpdatingResponse(true);
                    }
                } else {
                    return new Interaction.CustomIDUpdatingResponse(
                        new Interaction.MessageResponse(6),
                        new CustomIDListeningUpdate(null, null, null, null)
                    );
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse inGameStandInteraction(ComponentInteraction componentInteraction) {
            if (state != State.IN_GAME) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"inGame stand " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                if (participant.handState == Participant.HandState.UNFINISHED) {
                    participant.handState = Participant.HandState.STAND;
                    if (someoneCanMakeAMove()) {
                        return updateGameCustomIDUpdatingResponse(false);
                    } else {
                        setState(State.RESULTS);
                        return updateGameCustomIDUpdatingResponse(true);
                    }
                } else {
                   return new Interaction.CustomIDUpdatingResponse(
                        new Interaction.MessageResponse(6),
                        new CustomIDListeningUpdate(null, null, null, null)
                    ); 
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse inGameDoubleDownInteraction(ComponentInteraction componentInteraction) {
            if (state != State.IN_GAME) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"inGame doubleDown " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                if (participant.handState == Participant.HandState.UNFINISHED && 2 * participant.bet <= participant.currency && participant.hand.size() == 2) {
                    participant.bet *= 2;
                    handOutCard(participant);
                    int handValue = PlayingCard.getBlackjackValue(participant.hand);
                    if (handValue > 21) {
                        participant.handState = Game.Participant.HandState.BUST;
                    } else if (handValue == 21) {
                        participant.handState = Game.Participant.HandState.TWENTYONE;
                    } else {
                        // after doubling down, you can't take any more cards.
                        participant.handState = Game.Participant.HandState.STAND;
                    }
                    if (someoneCanMakeAMove()) {
                        return updateGameCustomIDUpdatingResponse(false);
                    } else {
                        setState(State.RESULTS);
                        return updateGameCustomIDUpdatingResponse(true);
                    }
                } else {
                    return new Interaction.CustomIDUpdatingResponse(
                        new Interaction.MessageResponse(6),
                        new CustomIDListeningUpdate(null, null, null, null)
                    );
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse inGameLeaveInteraction(ComponentInteraction componentInteraction) {
            if (state != State.IN_GAME) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"inGame leave " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                participant.playingState = Participant.PlayingState.LEFT;
                if (!activePlayersExist()) {
                    setState(State.FINISHED);
                    Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                    deleteGame();
                    return toReturn;
                } else {
                    return updateGameCustomIDUpdatingResponse(false);
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse inGameEndInteraction(ComponentInteraction componentInteraction) {
            if (state != State.IN_GAME) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"inGame end " + ID},
                        null,
                        null
                    )
                );
            } else if (componentInteraction.MEMBER.USER.ID == host.USER.ID) {
                setState(State.FINISHED);
                Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                deleteGame();
                return toReturn;
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse resultsReadyUpInteraction(ComponentInteraction componentInteraction) {
            if (state != State.RESULTS) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"results readyUp " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                participant.ready = true;
                if (activePlayersExist() && participantsStillToReadyUp() == 0) {
                    setState(State.BETTING_PHASE);
                    return updateGameCustomIDUpdatingResponse(true);
                } else {
                    return updateGameCustomIDUpdatingResponse(false);
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse resultsLeaveInteraction(ComponentInteraction componentInteraction) {
            if (state != State.RESULTS) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"results leave " + ID},
                        null,
                        null
                    )
                );
            } else if (getParticipantByUserID(componentInteraction.MEMBER.USER.ID) != null) {
                Participant participant = getParticipantByUserID(componentInteraction.MEMBER.USER.ID);
                participant.playingState = Participant.PlayingState.LEFT;
                if (!activePlayersExist()) {
                    setState(State.FINISHED);
                    Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                    deleteGame();
                    return toReturn;
                } else {
                    return updateGameCustomIDUpdatingResponse(false);
                }
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse resultsEndInteraction(ComponentInteraction componentInteraction) {
            if (state != State.RESULTS) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"results end " + ID},
                        null,
                        null
                    )
                );
            } else if (componentInteraction.MEMBER.USER.ID == host.USER.ID) {
                setState(State.FINISHED);
                Interaction.CustomIDUpdatingResponse toReturn = updateGameCustomIDUpdatingResponse(true);
                deleteGame();
                return toReturn;
            } else {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6),
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }
    }

    public static SlashCommand getSlashCommand() {
        SlashCommand blackjack = new SlashCommand(
            "blackjack",
            "Start a game of Blackjack",
            new SlashCommand.Option[] {
                new SlashCommand.Option(
                    "name",
                    "The name of your Blackjack game"
                ),
                new SlashCommand.Option(
                    "decks",
                    "How many decks of cards to use"
                )
            },
            new int[] {0},
            Blackjack::startGame
        );
        return blackjack;
    }

    public synchronized static Game createGame(String name, int decks, GuildMember host) {
        long id;
        // this would be crazy
        while (true) {
            id = RANDOM.nextLong();
            if (!idToGame.containsKey(id)) {
                break;
            }
        }
        Game game = new Game(id, decks, name, host);
        idToGame.put(id, game);
        game.addParticipant(host);
        return game;
    }
    
    public synchronized static Component[] getGameComponents(Game game) {
        if (game == null) {
            return null;
        } else {
            Game.State state = game.getState();
            if (state == Game.State.REGISTERED || state == Game.State.READY) { // on the first call, the state will only be registered, not ready
                String[] participantGlobalNames = game.participantGlobalNames();
                StringBuilder joinedPlayerListBuilder = new StringBuilder();
                for (String participantGlobalName : participantGlobalNames) {
                    joinedPlayerListBuilder.append("- " + participantGlobalName + "\\n");
                }
                String[] participantAvatars = game.participantAvatars();
                MediaGallery.Item[] participantAvatarGalleryItems = new MediaGallery.Item[participantAvatars.length];
                for (int i = 0; i < participantAvatars.length; i++) {
                    String avatarURL;
                    if (participantAvatars[i] == null) {
                        avatarURL = Game.Participant.defaultAvatar;
                    } else {
                        avatarURL = participantAvatars[i];
                    }
                    participantAvatarGalleryItems[i] = new MediaGallery.Item(
                        new UnfurledMediaItem(avatarURL),
                        null,
                        false
                    );
                }
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new Section(
                                new Component[] {
                                    new TextDisplay(
                                        "# " + game.name
                                    ),
                                    new TextDisplay(
                                        "**Blackjack**"
                                    ),
                                    new TextDisplay(
                                        "-# Hosted by " + game.host.USER.GLOBAL_NAME
                                    )
                                },
                                new Button(
                                    "ready start " + game.ID,
                                    1,
                                    "Start(Host only)"
                                )
                            ),
                            new Separator(
                                true,
                                1
                            ),
                            new MediaGallery(
                                participantAvatarGalleryItems
                            ),
                            new TextDisplay(
                                "### Currently joined: \\n" + joinedPlayerListBuilder.toString()
                            )
                        },
                        13369344 // boston university red
                    ),
                    new ActionRow(
                        new Component[] {
                            new Button(
                                "ready join " + game.ID,
                                1,
                                "Join"
                            ),
                            new Button(
                                "ready leave " + game.ID,
                                4,
                                "Leave"
                            )
                        }
                    )
                };
                return toReturn;
            } else if (state == Game.State.BETTING_PHASE) {
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new Section(
                                new Component[] {
                                    new TextDisplay(
                                        "# " + game.name
                                    ),
                                    new TextDisplay(
                                        "**Blackjack**"
                                    ),
                                    new TextDisplay(
                                        "-# Hosted by " + game.host.USER.GLOBAL_NAME
                                    )
                                },
                                new Button(
                                    "bettingPhase end " + game.ID,
                                    4,
                                    "End (Host only)"
                                )
                            ),
                            new Separator(
                                true,
                                1
                            ),
                            new TextDisplay(
                                "## All players, place a bet.\\n\\n" + getParticipantText(game)
                            )
                        },
                        13369344
                    ),
                    new ActionRow(
                        new Component[] {
                            new Button(
                                "bettingPhase betButton " + game.ID,
                                1,
                                "Bet"
                            ),
                            new Button(
                                "bettingPhase leave " + game.ID,
                                4,
                                "Leave"
                            )
                        }
                    )
                };
                return toReturn;
            } else if (state == Game.State.IN_GAME) {
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new Section(
                                new Component[] {
                                    new TextDisplay(
                                        "# " + game.name
                                    ),
                                    new TextDisplay(
                                        "**Blackjack**"
                                    ),
                                    new TextDisplay(
                                        "-# Hosted by " + game.host.USER.GLOBAL_NAME
                                    )
                                },
                                new Button(
                                    "inGame end " + game.ID,
                                    4,
                                    "End (Host only)"
                                )
                            ),
                            new Separator(
                                true,
                                1
                            ),
                            new TextDisplay(
                                "Cards left to deal: " + game.playingCardDealer.cardsLeft() + "/" + game.playingCardDealer.CARDS + " (reshuffle at 25%) \\n" + getParticipantText(game)
                            )
                        },
                        13369344
                    ),
                    new ActionRow(
                        new Component[] {
                            new Button(
                                "inGame hit " + game.ID,
                                3,
                                "Hit"
                            ),
                            new Button(
                                "inGame stand " + game.ID,
                                3,
                                "Stand"
                            ),
                            new Button(
                                "inGame doubleDown " + game.ID,
                                3,
                                "Double Down"
                            ),
                            new Button(
                                "inGame leave " + game.ID,
                                4,
                                "Leave"
                            )
                        }
                    )
                };
                return toReturn;
            } else if (state == Game.State.RESULTS) {
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new Section(
                                new Component[] {
                                    new TextDisplay(
                                        "# " + game.name
                                    ),
                                    new TextDisplay(
                                        "**Blackjack**"
                                    ),
                                    new TextDisplay(
                                        "-# Hosted by " + game.host.USER.GLOBAL_NAME
                                    )
                                },
                                new Button(
                                    "results end " + game.ID,
                                    4,
                                    "End (Host only)"
                                )
                            ),
                            new Separator(
                                true,
                                1
                            ),
                            new TextDisplay(
                                "Cards left to deal: " + game.playingCardDealer.cardsLeft() + "/" + game.playingCardDealer.CARDS + " (reshuffle at 25%) \\n" + getParticipantText(game)
                            )
                        },
                        13369344
                    ),
                    new ActionRow(
                        new Component[] {
                            new Button(
                                "results readyUp " + game.ID,
                                3,
                                "Ready up"
                            ),
                            new Button(
                                "results leave " + game.ID,
                                4,
                                "Leave"
                            )
                        }
                    )
                };
                return toReturn;
            } else if (state == Game.State.FINISHED) {
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new Section(
                                new Component[] {
                                    new TextDisplay(
                                        "# " + game.name
                                    ),
                                    new TextDisplay(
                                        "**Blackjack**"
                                    ),
                                    new TextDisplay(
                                        "-# Hosted by " + game.host.USER.GLOBAL_NAME
                                    )
                                },
                                new Thumbnail(
                                    new UnfurledMediaItem(
                                        "https://cdn.discordapp.com/attachments/1404495386424381500/1408788031128735784/7lor6v.png?ex=68ab0398&is=68a9b218&hm=6e7061926fe17392b598fc7abb519f6bf0eac1cfbc1edf50011d25034dae5460&"
                                    ),
                                    null,
                                    false
                                )
                            ),
                            new TextDisplay(
                                "## This game is over! \\n" + getParticipantText(game)
                            )
                        },
                        13369344
                    )
                };
                return toReturn;
            } else {
                return null;
            }
        }
    }

    public static synchronized Component[] getBettingModalComponents(Game game, Game.Participant participant) {
        if (game == null) {
            return null;
        } else {
            return new Component[] {
                new Label(
                    "How much do you want to bet?",
                    "You have " + participant.currency,
                    new TextInput(
                        "bettingPhase inputBet " + game.ID,
                        1,
                        "Your bet"
                    )
                )
            };
        }
    }

    public static synchronized String getParticipantText(Game game) {
        if (game == null) {
            return null;
        } else if (game.state == Game.State.BETTING_PHASE) {
            StringBuilder toReturnBuilder = new StringBuilder();
            for (Game.Participant participant : game.participants) {
                toReturnBuilder.append("**" + participant.MEMBER.USER.GLOBAL_NAME + "** (has " + participant.currency + ")\\n");
                if (participant.playingState == Game.Participant.PlayingState.BANKRUPT) {
                    toReturnBuilder.append("*Bankruptcy* - out of the game.\\n");
                } else if (participant.playingState == Game.Participant.PlayingState.HAS_BET) {
                    toReturnBuilder.append("Betting **" + participant.bet + "**\\n");
                } else if (participant.playingState == Game.Participant.PlayingState.NOT_YET_BET) {
                    toReturnBuilder.append("You still need to **bet**!\\n");
                } else {
                    toReturnBuilder.append("Left the game.\\n");
                }
                toReturnBuilder.append("\\n");
            }
            return toReturnBuilder.toString();
        } else if (game.state == Game.State.IN_GAME) {
            StringBuilder toReturnBuilder = new StringBuilder();
            toReturnBuilder.append("**Dealer**\\n**Hand:** " + PlayingCard.back + game.dealerHand.get(1).getCharacter() + " (?, " + game.dealerHand.get(1).getFaceValueName() +  ")\\n");
            for (Game.Participant participant : game.participants) {
                if (participant.playingState == Game.Participant.PlayingState.BANKRUPT) {
                    toReturnBuilder.append("**" + participant.MEMBER.USER.GLOBAL_NAME + "** (has " + participant.currency + ")\\n");
                    toReturnBuilder.append("*Bankruptcy* - out of the game.\\n");
                } else if (participant.playingState == Game.Participant.PlayingState.HAS_BET) {
                    toReturnBuilder.append("**" + participant.MEMBER.USER.GLOBAL_NAME + "** bets " + participant.bet + " (has " + participant.currency + ")\\n");
                    toReturnBuilder.append("**Hand:** " + PlayingCard.getCharactersFromList(participant.hand) + " (" + PlayingCard.getFaceValueNamesFromList(participant.hand) + ")");
                    if (participant.handState == Game.Participant.HandState.STAND) {
                        toReturnBuilder.append(" **Standing.**");
                    } else if (participant.handState == Game.Participant.HandState.TWENTYONE) {
                        toReturnBuilder.append(" **Perfect Hand.**");
                    } else if (participant.handState == Game.Participant.HandState.BUST) {
                        toReturnBuilder.append(" **Bust.**");
                    }
                } else {
                    toReturnBuilder.append("Left the game.");
                }
                toReturnBuilder.append("\\n");
            }
            return toReturnBuilder.toString();
        } else if (game.state == Game.State.RESULTS) {
            StringBuilder toReturnBuilder = new StringBuilder();
            if (game.dealerHandState == Game.Participant.HandState.BUST) {
                toReturnBuilder.append("**Dealer**\\n**Hand:** " + PlayingCard.getCharactersFromList(game.dealerHand) + " **Bust.**\\n");
            } else {
                toReturnBuilder.append("**Dealer**\\n**Hand:** " + PlayingCard.getCharactersFromList(game.dealerHand) + "\\n");
            }
            for (Game.Participant participant : game.participants) {
                if (participant.playingState == Game.Participant.PlayingState.BANKRUPT) {
                    toReturnBuilder.append("**" + participant.MEMBER.USER.GLOBAL_NAME + "** (has " + participant.currency + ")\\n");
                    // if the participant just turned bankrupt this round, we can still access their hand.
                    toReturnBuilder.append("*Bankruptcy* - out of the game.\\n");
                } else if (participant.playingState == Game.Participant.PlayingState.LEFT) {
                    toReturnBuilder.append("Left the game.\\n");
                } else {
                    // we know for sure that at this point there is no player that hasn't betted.
                    int dealerHandValue = PlayingCard.getBlackjackValue(game.dealerHand);
                    int participantHandValue = PlayingCard.getBlackjackValue(participant.hand);
                    toReturnBuilder.append("**" + participant.MEMBER.USER.GLOBAL_NAME + "** bet " + participant.bet + " (has " + participant.currency + ")\\n");
                    toReturnBuilder.append("**Hand:** " + PlayingCard.getCharactersFromList(participant.hand)+ " (" + PlayingCard.getFaceValueNamesFromList(participant.hand) + ")");
                    if (participant.handState == Game.Participant.HandState.BUST) {
                        toReturnBuilder.append(" **Lose** - Bust.\\n");
                    } else if (game.dealerHandState == Game.Participant.HandState.BUST) {
                        toReturnBuilder.append(" **Win** - Dealer bust.\\n");
                    } else if (dealerHandValue > participantHandValue) {
                        toReturnBuilder.append(" **Lose** - Lower than dealer.\\n");
                    } else if (dealerHandValue == participantHandValue) {
                        int dealerHandSize = game.dealerHand.size();
                        int participantHandSize = participant.hand.size();
                        if (dealerHandSize == 2 && participantHandSize == 2) {
                            toReturnBuilder.append(" **Tie** - Equal to dealer.\\n");
                        } else if (dealerHandSize == 2) {
                            toReturnBuilder.append(" **Lose** - Dealer has Blackjack.\\n");
                        } else if (participantHandSize == 2) {
                            toReturnBuilder.append(" **Win** - Player has Blackjack.\\n");
                        } else {
                            toReturnBuilder.append(" **Tie** - Equal to dealer.\\n");
                        }
                    } else {
                        toReturnBuilder.append(" **Win** - Higher than dealer.\\n");
                    }
                }
                toReturnBuilder.append("\\n");
            }
            int peopleWhoStillNeedToReadyUp = game.participantsStillToReadyUp();
            if (!game.activePlayersExist()) {
                toReturnBuilder.append("There are no active players left!");
            } else {
                toReturnBuilder.append("To start a new round, **" + peopleWhoStillNeedToReadyUp + "** must still ready up.");
            }
            return toReturnBuilder.toString();
        } else if (game.state == Game.State.FINISHED) {
            StringBuilder toReturnBuilder = new StringBuilder();
            // we know at this point that the list of participants has already been sorted by currency
            Game.Participant currentParticipant;
            for (int i = 0; i < game.participants.size(); i++) {
                currentParticipant = game.participants.get(i);
                toReturnBuilder.append("**" + (i + 1) + ". " + currentParticipant.MEMBER.USER.GLOBAL_NAME + ": " + currentParticipant.currency + " **\\n");
            }
            return toReturnBuilder.toString();
        } else {
            return null;
        }
    }

    // this returns a map of the necessary custom id notification functions the game has to listen to during a specific state
    private static Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> getGameComponentNotificationCustomIDNotificationFunctions(Game game, Game.State state) {
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> toReturn
            = new TreeMap<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>>();
        if (state == Game.State.REGISTERED || state == Game.State.READY) {
            toReturn.put("ready join " + game.ID, game::readyJoinInteraction);
            toReturn.put("ready leave " + game.ID, game::readyLeaveInteraction);
            toReturn.put("ready start " + game.ID, game::readyStartInteraction);
        } else if (state == Game.State.BETTING_PHASE) {
            toReturn.put("bettingPhase betButton " + game.ID, game::bettingPhaseBetButtonInteraction);
            toReturn.put("bettingPhase leave " + game.ID, game::bettingPhaseLeaveInteraction);
            toReturn.put("bettingPhase end " + game.ID, game::bettingPhaseEndInteraction);
        } else if (state == Game.State.IN_GAME) {
            toReturn.put("inGame hit " + game.ID, game::inGameHitInteraction);
            toReturn.put("inGame stand " + game.ID, game::inGameStandInteraction);
            toReturn.put("inGame doubleDown " + game.ID, game::inGameDoubleDownInteraction);
            toReturn.put("inGame leave " + game.ID, game::inGameLeaveInteraction);
            toReturn.put("inGame end " + game.ID, game::inGameEndInteraction);
        } else if (state == Game.State.RESULTS) {
            toReturn.put("results readyUp " + game.ID, game::resultsReadyUpInteraction);
            toReturn.put("results leave " + game.ID, game::resultsLeaveInteraction);
            toReturn.put("results end " + game.ID, game::resultsEndInteraction);
        } else if (state == Game.State.FINISHED) {

        }
        return toReturn;
    }
    
    private static Map<String, Function<ModalInteraction, Interaction.CustomIDUpdatingResponse>> getGameModalNotificationCustomIDNotificationFunctions(Game game, Game.State state) {
        Map<String, Function<ModalInteraction, Interaction.CustomIDUpdatingResponse>> toReturn
            = new TreeMap<String, Function<ModalInteraction, Interaction.CustomIDUpdatingResponse>>();
        if (state == Game.State.REGISTERED || state == Game.State.READY) {
            
        } else if (state == Game.State.BETTING_PHASE) {
            toReturn.put("bettingPhase betModal " + game.ID, game::bettingPhaseBetModalInteraction);
        } else if (state == Game.State.IN_GAME) {

        } else if (state == Game.State.RESULTS) {

        } else if (state == Game.State.FINISHED) {

        }
        return toReturn;
    }

    // returns a CustomIDListeningUpdate for switchting between game states.
    private static CustomIDListeningUpdate getGameStateChangeCustomIDListeningUpdate(Game game, Game.State newState, Game.State oldState) {
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> state1ComponentNotificationCustomIDs
            = getGameComponentNotificationCustomIDNotificationFunctions(game, oldState);
        Map<String, Function<ModalInteraction, Interaction.CustomIDUpdatingResponse>> state1ModalNotificationCustomIDs
            = getGameModalNotificationCustomIDNotificationFunctions(game, oldState);
        Set<String> state1ComponentNotificationCustomIDSet = state1ComponentNotificationCustomIDs.keySet();
        Set<String> state1ModalNotificationCustomIDSet = state1ModalNotificationCustomIDs.keySet();
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> state2ComponentNotificationCustomIDs
            = getGameComponentNotificationCustomIDNotificationFunctions(game, newState);
        Map<String, Function<ModalInteraction, Interaction.CustomIDUpdatingResponse>> state2ModalNotificationCustomIDs
            = getGameModalNotificationCustomIDNotificationFunctions(game, newState);
        CustomIDListeningUpdate toReturn = new CustomIDListeningUpdate(
            state2ComponentNotificationCustomIDs,
            state1ComponentNotificationCustomIDSet.toArray(new String[state1ComponentNotificationCustomIDSet.size()]),
            state2ModalNotificationCustomIDs,
            state1ModalNotificationCustomIDSet.toArray(new String[state1ModalNotificationCustomIDSet.size()])
        );
        return toReturn;
    }
    
    public static Interaction.CustomIDUpdatingResponse startGame(SlashCommandInteraction slashCommandInteraction) {
        Interaction.MessageResponse response = new Interaction.MessageResponse(4);
        int decks;
        try {
            decks = Integer.valueOf(slashCommandInteraction.DATA.OPTIONS[1].STRING_VALUE);
        } catch (NumberFormatException numberFormatException) {
            response.data.flags = (int) Math.pow(2, 6); // it's an ephemeral message that only the sender of the command can see
            response.data.content = "You need to enter a positive integer between " + Blackjack.MIN_DECKS + " and " + Blackjack.MAX_DECKS;
            return new Interaction.CustomIDUpdatingResponse(
                response,
                new CustomIDListeningUpdate(null, null, null, null)
            );
        }
        if (decks > MAX_DECKS || decks < MIN_DECKS) {
            response.data.flags = (int) Math.pow(2, 6); // it's an ephemeral message that only the sender of the command can see
            response.data.content = "You need to enter a positive integer between " + Blackjack.MIN_DECKS + " and " + Blackjack.MAX_DECKS;
            return new Interaction.CustomIDUpdatingResponse(
                response,
                new CustomIDListeningUpdate(null, null, null, null)
            );
        }
        Game newGame = createGame(
            slashCommandInteraction.DATA.OPTIONS[0].STRING_VALUE,
            decks,
            slashCommandInteraction.MEMBER
        );
        newGame.setState(Game.State.READY);
        response.data.flags = (int) Math.pow(2, 15);
        response.data.components = getGameComponents(newGame);
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> componentNotificationStartListening
            = new TreeMap<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>>();
        componentNotificationStartListening.putAll(getGameComponentNotificationCustomIDNotificationFunctions(newGame, newGame.getState()));
        return new Interaction.CustomIDUpdatingResponse(
            response,
            new CustomIDListeningUpdate(
                componentNotificationStartListening,
                null,
                null,
                null
            )
        );
    }
}