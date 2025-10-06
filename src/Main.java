import commands.games.Blackjack;
import commands.utility.RandomChallenge;
import eventhandling.QuestpressoEventReceiver;
import unrelibrary.DiscordBot;
import unrelibrary.formatting.GeneralFormatter;

public class Main {

    public static void main(String[] args) {
        DiscordBot discordBot = new DiscordBot(false, 80, false, "./data/confidential.json", true);

        discordBot.initialize(new QuestpressoEventReceiver());

        discordBot.registerSlashCommand("blackjack", Blackjack.getSlashCommand());
        try {
            discordBot.registerSlashCommand("randomchallenge", RandomChallenge.getSlashCommand("./data/challenges.json", discordBot));
        } catch (Exception exception) {
            GeneralFormatter.printException("That didn't work. ", exception);
        }
        

        discordBot.goOnline();
        discordBot.control();
        
        discordBot.goOffline();
    }
}
