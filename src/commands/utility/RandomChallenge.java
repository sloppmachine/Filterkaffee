package commands.utility;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import unrelibrary.discordobjects.interactions.SlashCommandInteraction;
import unrelibrary.DiscordBot;
import unrelibrary.MalformedException;
import unrelibrary.discordobjects.components.ActionRow;
import unrelibrary.discordobjects.components.Button;
import unrelibrary.discordobjects.components.Component;
import unrelibrary.discordobjects.components.Container;
import unrelibrary.discordobjects.components.MediaGallery;
import unrelibrary.discordobjects.components.Separator;
import unrelibrary.discordobjects.components.StringSelect;
import unrelibrary.discordobjects.components.TextDisplay;
import unrelibrary.discordobjects.components.UnfurledMediaItem;
import unrelibrary.discordobjects.interactions.ComponentInteraction;
import unrelibrary.discordobjects.interactions.Interaction;
import unrelibrary.formatting.GeneralFormatter;
import unrelibrary.formatting.JSONFactory;
import unrelibrary.restapi.CustomIDListeningUpdate;
import unrelibrary.restapi.ServerResponseException;
import unrelibrary.restapi.SlashCommand;


// the self destruct button breaks custom id convention because it isn't bound to a specific state
// if a person uses this command multiple times, the command only listens to the last message.

public class RandomChallenge {
    private static Map<Long, OutputMessage> usersToOutputMessages = new TreeMap<Long, OutputMessage>();
    public static Entry[] entries;
    public static StringSelect.Option[] entryStringSelectionOptions;
    public static Map<Long, OutputMessage> idToOutputMessage = new TreeMap<Long, OutputMessage>();
    private static final Random RANDOM = new Random(System.currentTimeMillis());
    private static DiscordBot DISCORD_BOT;

    public static class Entry {
        public final String GAME;
        public final String[] CHALLENGES;

        public Entry(String game, String[] challenges) {
            this.GAME = game;
            this.CHALLENGES = challenges;
        }
    }

    // this is the message in the channel that shows the animation and results and can be interacted with
    public static class OutputMessage {
        public static enum State {
            REGISTERED, // registered but not send
            READY, // the message is in the chat
            RESULTS, // the message is displaying a challenge after a game has been selected
        }
        public final long ID;
        public Entry currentlySelectedGame = null;
        public String result;
        private State state;
        private State lastState;

        public OutputMessage(long id) {
            this.ID = id;
        }

        public State getState() {
            return state;
        }

        public State getLastState() {
            return lastState;
        }

        public void setState(State state) {
            lastState = this.state;
            this.state = state;
            if (state == State.RESULTS) {
                // at this point, a game should have been selected anyway. still
                if (currentlySelectedGame != null) {
                    result = currentlySelectedGame.CHALLENGES[RandomChallenge.RANDOM.nextInt(currentlySelectedGame.CHALLENGES.length)];
                } else {
                    result = null;
                }
            }
            return;
        }

        public void selectGameByName(String name) {
            for (RandomChallenge.Entry entry : RandomChallenge.entries) {
                if (entry.GAME.equals(name)) {
                    currentlySelectedGame = entry;
                    return;
                }
            }
            currentlySelectedGame = null;
            return;
        }

        public synchronized Interaction.CustomIDUpdatingResponse updateOutputMessageCustomIDResponse(boolean newState) {
            Interaction.MessageResponse response = new Interaction.MessageResponse(7); // this means editing the original message
            response.data.flags = (int) Math.pow(2, 15);
            response.data.components = getOutputMessageComponents(this);
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

        public Interaction.CustomIDUpdatingResponse readySelectGameInteraction(ComponentInteraction componentInteraction) {
            if (state != State.READY) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"ready select " + ID},
                        null,
                        null
                    )
                );
            } else {
                selectGameByName(componentInteraction.DATA.VALUES[0]);
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(null, null, null, null)
                );
            }
        }

        public Interaction.CustomIDUpdatingResponse readyGetChallengeInteraction(ComponentInteraction componentInteraction) {
            if (state != State.READY || currentlySelectedGame == null) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"ready getChallenge " + ID},
                        null,
                        null
                    )
                );
            } else {
                setState(State.RESULTS);
                return updateOutputMessageCustomIDResponse(true);
            }
        }

        public Interaction.CustomIDUpdatingResponse resultsResetInteraction(ComponentInteraction componentInteraction) {
            if (state != State.RESULTS) {
                return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"results reset " + ID},
                        null,
                        null
                    )
                );
            } else {
                setState(State.READY);
                return updateOutputMessageCustomIDResponse(true);
            }
        }

        public Interaction.CustomIDUpdatingResponse selfDestructInteraction(ComponentInteraction componentInteraction) {
            try {
                DISCORD_BOT.apiCommunicationManager.restManager.deleteMessage(componentInteraction.CHANNEL_ID, componentInteraction.MESSAGE.ID);
            } catch (ServerResponseException serverResponseException) {
                //CONTINUE HERE
                GeneralFormatter.printException("somethign went wrong", serverResponseException);
            }
            return new Interaction.CustomIDUpdatingResponse(
                    new Interaction.MessageResponse(6), // acknowledge but don't do anything
                    new CustomIDListeningUpdate(
                        null,
                        new String[] {"results reset " + ID},
                        null,
                        null
                    )
                );
        }
    }

    public static void loadEntries(String file) throws FileNotFoundException, MalformedException {
        File dataFile = new File(file);
        Scanner scanner = new Scanner(dataFile);
        StringBuilder fileContents = new StringBuilder();
        while (scanner.hasNextLine()) {
            fileContents.append(scanner.nextLine());
        }
        Map<String, String> rawSeparatedJSON = GeneralFormatter.separateJSON(fileContents.toString());
        String[] rawSeparatedEntries = GeneralFormatter.separateArray(rawSeparatedJSON.get("\"entries\""));
        entries = new Entry[rawSeparatedEntries.length];
        entryStringSelectionOptions = new StringSelect.Option[rawSeparatedEntries.length];
        for (int i = 0; i < rawSeparatedEntries.length; i++) {
            String rawEntry = rawSeparatedEntries[i];
            Map<String, String> rawSeparatedEntry = GeneralFormatter.separateJSON(rawEntry);
            String gameName = JSONFactory.extractString(rawSeparatedEntry.get("\"game\""));
            String[] rawChallenges = GeneralFormatter.separateArray(rawSeparatedEntry.get("\"challenges\""));
            String[] challenges = new String[rawChallenges.length];
            for (int challengeIndex = 0; challengeIndex < challenges.length; challengeIndex++) {
                challenges[challengeIndex] = JSONFactory.extractString(rawChallenges[challengeIndex]);
            }
            entries[i] = new Entry(
                gameName,
                challenges
            );
            entryStringSelectionOptions[i] = new StringSelect.Option(
                gameName,
                gameName,
                "testest"
            );
        }
        scanner.close();
        return;
    }

    private static Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> getGameComponentNotificationCustomIDNotificationFunctions(OutputMessage outputMessage, OutputMessage.State state) {
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> toReturn
            = new TreeMap<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>>();
        if (state == OutputMessage.State.REGISTERED || state == OutputMessage.State.READY) {
            toReturn.put("ready select " + outputMessage.ID, outputMessage::readySelectGameInteraction);
            toReturn.put("ready getChallenge " + outputMessage.ID, outputMessage::readyGetChallengeInteraction);
            toReturn.put("selfDestruct " + outputMessage.ID, outputMessage::selfDestructInteraction);
        } else if (state == OutputMessage.State.RESULTS) {
            toReturn.put("results reset " + outputMessage.ID, outputMessage::resultsResetInteraction);
            toReturn.put("selfDestruct " + outputMessage.ID, outputMessage::selfDestructInteraction);
        }
        return toReturn;
    }

    // returns a CustomIDListeningUpdate for switchting between game states.
    private static CustomIDListeningUpdate getGameStateChangeCustomIDListeningUpdate(OutputMessage outputMessage, OutputMessage.State newState, OutputMessage.State oldState) {
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> state1ComponentNotificationCustomIDs
            = getGameComponentNotificationCustomIDNotificationFunctions(outputMessage, oldState);
        Set<String> state1ComponentNotificationCustomIDSet = state1ComponentNotificationCustomIDs.keySet();
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> state2ComponentNotificationCustomIDs
            = getGameComponentNotificationCustomIDNotificationFunctions(outputMessage, newState);
        CustomIDListeningUpdate toReturn = new CustomIDListeningUpdate(
            state2ComponentNotificationCustomIDs,
            state1ComponentNotificationCustomIDSet.toArray(new String[state1ComponentNotificationCustomIDSet.size()]),
            null,
            null
        );
        return toReturn;
    }

    public static Component[] getOutputMessageComponents(OutputMessage outputMessage) {
        if (outputMessage == null) {
            return null;
        } else {
            OutputMessage.State state = outputMessage.state;
            if (state == OutputMessage.State.REGISTERED || state == OutputMessage.State.READY) {
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new TextDisplay(
                                "# Get a random Challenge!"
                            ),
                            new Separator(
                                true,
                                1
                            ),
                            new MediaGallery(
                                new MediaGallery.Item[] {
                                    new MediaGallery.Item(
                                        new UnfurledMediaItem("https://cdn.discordapp.com/attachments/1416447713137918122/1416458293563490376/reddit-gambling-short.gif?ex=68c6eb15&is=68c59995&hm=48de03227dfc031eb67e73fdf99bef1d6596fd784f2b1e74ed7cbd49a7de4d14&"),
                                        null,
                                        false
                                    )
                                }
                            ),
                            new ActionRow(
                                new Component[] {
                                    new StringSelect(
                                        "ready select " + outputMessage.ID,
                                        entryStringSelectionOptions,
                                        "placeholder"
                                    )
                                }
                            )
                        },
                        13369344
                    ),
                    new ActionRow(
                        new Component[] {
                            new Button(
                                "ready getChallenge " + outputMessage.ID,
                                4,
                                "Big round red button"
                            ),
                            new Button(
                                "selfDestruct " + outputMessage.ID,
                                1,
                                "Self destruct"
                            )
                        }
                    )
                };
                return toReturn;
            } else if (state == OutputMessage.State.RESULTS) {
                Component[] toReturn = new Component[] {
                    new Container(
                        new Component[] {
                            new TextDisplay(
                                "# Get a random Challenge!"
                            ),
                            new Separator(
                                true,
                                1
                            ),
                            new MediaGallery(
                                new MediaGallery.Item[] {
                                    new MediaGallery.Item(
                                        new UnfurledMediaItem("https://media.discordapp.net/attachments/1416447713137918122/1416447840468733973/reddit-gambling.gif?ex=68ce2199&is=68ccd019&hm=aa45fa99bbd9db05ea23537e52ff5cc09bc2f97f737784a09ffdd237a500472d&="),
                                        null,
                                        false
                                    )
                                }
                            ),
                            new TextDisplay(
                                "Your challenge is: " + outputMessage.result
                            )
                        },
                        13369344
                    ),
                    new ActionRow(
                        new Component[] {
                            new Button(
                                "results reset " + outputMessage.ID,
                                4,
                                "Big round red button"
                            ),
                            new Button(
                                "selfDestruct " + outputMessage.ID,
                                1,
                                "Self destruct"
                            )
                        }
                    )
                };
                return toReturn;
            } else {
                return null;
            }
        }
    }

    public static Interaction.CustomIDUpdatingResponse createOutputMessage(SlashCommandInteraction slashCommandInteraction) {
        long userID;
        if (slashCommandInteraction.MEMBER != null) {
            userID = slashCommandInteraction.MEMBER.USER.ID;
        } else {
            userID = slashCommandInteraction.USER.ID;
        }
        String[] oldMessageCustomIDs = null; // this stays null if there is no old message
        if (usersToOutputMessages.containsKey(userID)) {
            OutputMessage oldOutputMessage = usersToOutputMessages.get(userID);
            Set<String> oldOutputMessageCustomIDKeySet = getGameComponentNotificationCustomIDNotificationFunctions(oldOutputMessage, oldOutputMessage.state).keySet();
            oldMessageCustomIDs = oldOutputMessageCustomIDKeySet.toArray(new String[oldOutputMessageCustomIDKeySet.size()]);
        }

        Interaction.MessageResponse response = new Interaction.MessageResponse(4);
        long id;
        // this would be crazy
        while (true) {
            id = RANDOM.nextLong();
            if (!idToOutputMessage.containsKey(id)) {
                break;
            }
        }
        OutputMessage newOutputMessage = new OutputMessage(id);
        usersToOutputMessages.put(userID, newOutputMessage);
        newOutputMessage.setState(OutputMessage.State.READY);
        response.data.flags = (int) Math.pow(2, 15);
        response.data.components = getOutputMessageComponents(newOutputMessage);
        Map<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>> componentNotificationStartListening
            = new TreeMap<String, Function<ComponentInteraction, Interaction.CustomIDUpdatingResponse>>();
        componentNotificationStartListening.putAll(getGameComponentNotificationCustomIDNotificationFunctions(newOutputMessage, newOutputMessage.getState()));
        return new Interaction.CustomIDUpdatingResponse(
            response,
            new CustomIDListeningUpdate(
                componentNotificationStartListening,
                oldMessageCustomIDs,
                null,
                null
            )
        );
    }

    public static SlashCommand getSlashCommand(String fileToLoad, DiscordBot discordBot) throws FileNotFoundException, MalformedException {
        loadEntries(fileToLoad);
        DISCORD_BOT = discordBot;
        SlashCommand randomChallenge = new SlashCommand(
            "randomchallenge",
            "Get a random challenge!",
            new SlashCommand.Option[] {},
            new int[] {0, 1},
            RandomChallenge::createOutputMessage
        );
        return randomChallenge;
    }
}