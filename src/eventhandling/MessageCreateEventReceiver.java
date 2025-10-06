package eventhandling;

import unrelibrary.discordobjects.Message;
import unrelibrary.restapi.ServerResponseException;
import unrelibrary.APICommunicationManager;

// static method set for receiving events where a message was created
public class MessageCreateEventReceiver {
    public static long adminChannelID = 0L; // add your admin channel here

    public static void adminSendCommand(String input, Message originalMessage, APICommunicationManager apiCommunicationManager) {
        input = input.trim();
        int indexOfSpace = input.indexOf(" ");
        if (indexOfSpace == -1) {
            // this requires a channel id.
        } else {
            try {
                Long channelID = Long.valueOf(input.substring(0, indexOfSpace));
                String messageContent = input.substring(indexOfSpace, input.length());
                apiCommunicationManager.restManager.sendMessage(channelID, messageContent);
            } catch (NumberFormatException numberFormatException) {
                // do nothing
            } catch (ServerResponseException serverResponseException) {
                // do nothing
            }
            
        }
        return;
    }

    public static void parseAsAdmin(String input, Message originalMessage, APICommunicationManager apiCommunicationManager) {
        input = input.trim();
        int indexOfSpace = input.indexOf(" ");
        if (indexOfSpace == -1) {

        } else {
            String command = input.substring(0, indexOfSpace);
            String argument = input.substring(indexOfSpace, input.length());
            // these commands require admin permission (or sending in an admin channel)
            if (command.equals("send")) {
                adminSendCommand(argument, originalMessage, apiCommunicationManager);
            }
        }
        return;
    }

    public static void accept(Message message, APICommunicationManager apiCommunicationManager) {
        String input = message.CONTENT.trim();
        int indexOfSpace = input.indexOf(" ");
        if (indexOfSpace == -1) {

        } else {
            String command = input.substring(0, indexOfSpace);
            String argument = input.substring(indexOfSpace, input.length());
            if (command.equals("admin")) {
                // admin channels are required to use admin
                if (message.CHANNEL_ID == adminChannelID) {
                    parseAsAdmin(argument, message, apiCommunicationManager);
                }
            }
        }
        return;
    }
}
