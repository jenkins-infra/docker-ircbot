package org.jenkinsci.backend.ircbot.fallback;

import org.apache.commons.lang3.StringUtils;

/**
 * This class is mostly to separate the core code from the joke-based one. This is VAI: very artificial intelligence.
 * The code below is generally intended to be called after normal commands have been parsed, and nothing was found.
 * <p>
 * So... Main rule below is to be creative with answers :-).
 */
public class FallbackMessage {
    private final String payload;
    private final String sender;

    public FallbackMessage(String payload, String sender) {
        this.payload = payload;
        this.sender = sender;
    }

    public String answer() {
        if (StringUtils.containsIgnoreCase(payload, "thank")) {
            return new WeightedRandomAnswer()
                    .addAnswer("You're welcome", 5)
                    .addAnswer("my pleasure", 3)
                    .addAnswer("no worries, mate", 2)
                    .addAnswer("no drama, mate", 1) // https://www.daytranslations.com/blog/2013/01/australian-slang-a-unique-way-of-saying-and-describing-things-524/
                    .get();
        }

        if (StringUtils.startsWithIgnoreCase(payload, "hello")) {
            return "Hello, " + sender + "!";
        }

        return new WeightedRandomAnswer()
                .addAnswer("I didn't understand the command", 4)
                .addAnswer("Say it again?", 3)
                .addAnswer("Come again?", 2)
                .addAnswer("Wut?", 2)
                .addAnswer("Gnih?", 1)
                .get();
    }

}
