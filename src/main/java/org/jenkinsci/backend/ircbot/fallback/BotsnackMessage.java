package org.jenkinsci.backend.ircbot.fallback;

import org.apache.commons.lang3.StringUtils;

public class BotsnackMessage {

    public String answer() {
        return new WeightedRandomAnswer()
                .addAnswer("Yum!", 4)
                .addAnswer("Om nom nom", 4)
                .addAnswer("Delish!", 3)
                .addAnswer("Thanks for the treat!", 3)
                .addAnswer("Mmmmm, can I have another?", 2)
                .addAnswer("Woot Woot", 2)
                .addAnswer("Where did you buy these delicious snacks?", 1)
                .get();
    }
}
