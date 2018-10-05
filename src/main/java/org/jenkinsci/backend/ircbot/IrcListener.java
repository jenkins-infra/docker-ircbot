package org.jenkinsci.backend.ircbot;

import com.atlassian.jira.rest.client.api.ComponentRestClient;
import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.api.domain.AssigneeType;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.Comment;
import com.atlassian.jira.rest.client.api.domain.Component;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.ComponentInput;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.util.concurrent.Promise;
import org.apache.commons.lang.StringUtils;
import org.pircbotx.*;
import org.pircbotx.cap.SASLCapHandler;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.MessageEvent;
import org.kohsuke.github.GHEvent;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHOrganization.Permission;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.pircbotx.output.OutputChannel;
import org.pircbotx.output.OutputIRC;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.*;
import javax.annotation.CheckForNull;

/**
 * IRC Bot on irc.freenode.net as a means to delegate administrative work to committers.
 *
 * @author Kohsuke Kawaguchi
 */
public class IrcListener extends ListenerAdapter {
    private static final String FORK_TO_JIRA_FIELD = "customfield_10321";
    private static final String FORK_FROM_JIRA_FIELD = "customfield_10320";
    private static final String USER_LIST_JIRA_FIELD = "customfield_10323";
    private static final String DONE_JIRA_RESOLUTION_NAME = "Done";

    /**
     * Records commands that we didn't understand.
     */
    private File unknownCommands;

    private final Random random = new Random(System.currentTimeMillis());

    /**
     * Map from the issue number to the time it was last mentioned.
     * Used so that we don't repeatedly mention the same issues.
     */
    @SuppressWarnings("unchecked")
    private final Map<String,Long> recentIssues = Collections.<String,Long>synchronizedMap(new HashMap<String,Long>(10));

    public IrcListener(File unknownCommands) {
        this.unknownCommands = unknownCommands;
    }

    @Override
    public void onMessage(MessageEvent e) {
        Channel channel = e.getChannel();
        User sender = e.getUser();
        String message = e.getMessage();

        String senderNick = sender.getNick();

        if (!IrcBotConfig.getChannels().contains(channel.getName()))     return; // not in this channel
        if (senderNick.equals("jenkinsci_builds") || senderNick.equals("jenkins-admin") || senderNick.startsWith("ircbot-"))
            return; // ignore messages from other bots
        final String directMessagePrefix = e.getBot().getNick() + ":";

        message = message.trim();
        try {
            if (message.startsWith(directMessagePrefix)) { // Direct command to the bot
                // remove prefixes, trim whitespaces
                String payload = message.substring(directMessagePrefix.length()).trim();
                payload = payload.replaceAll("\\s+", " ");
                handleDirectCommand(channel, sender, payload);
            } else {   // Just a commmon message in the chat
                replyBugStatuses(channel, message);
            }
        } catch (RuntimeException ex) { // Catch unhandled runtime issues
            ex.printStackTrace();
            channel.send().message("An error ocurred in the Bot. Please submit a bug to Jenkins INFRA project.");
            channel.send().message(ex.getMessage());
            throw ex; // Propagate the error to the caller in order to let it log and handle the issue
        }
    }
        
    private void replyBugStatuses(Channel channel, String message) {
        Matcher m = Pattern.compile("(?:hudson-|jenkins-|bug )([0-9]{2,})",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"JENKINS-"+m.group(1));
        }

        m = Pattern.compile("(?:infra-)([0-9]+)",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"INFRA-"+m.group(1));
        }
        
        m = Pattern.compile("(?:website-)([0-9]+)",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"WEBSITE-"+m.group(1));
        }
        
        m = Pattern.compile("(?:hosting-)([0-9]+)",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"HOSTING-"+m.group(1));
        }
        
        m = Pattern.compile("(?:events-)([0-9]+)",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"EVENTS-"+m.group(1));
        }
        
        m = Pattern.compile("(?:ux-)([0-9]+)",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"UX-"+m.group(1));
        }
        
        m = Pattern.compile("(?:test-)([0-9]+)",CASE_INSENSITIVE).matcher(message);
        while (m.find()) {
            replyBugStatus(channel,"TEST-"+m.group(1));
        }
    }
    
    /**
     * Handles direct commands coming to the bot.
     * The handler presumes the external trimming of the payload.
     */
    private void handleDirectCommand(Channel channel, User sender, String payload) {
        Matcher m;

        m = Pattern.compile("(?:create|make|add) (\\S+)(?: repository)? (?:on|in) github(?: for (\\S+))?",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            createGitHubRepository(channel, sender, m.group(1), m.group(2));
            return;
        }

        m = Pattern.compile("fork (?:https://github\\.com/)?(\\S+)/(\\S+)(?: on github)?(?: as (\\S+))?",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            forkGitHub(channel, sender, m.group(1),m.group(2),m.group(3));
            return;
        }
        
        m = Pattern.compile("rename (?:github )repo (\\S+) to (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            renameGitHubRepo(channel, sender, m.group(1), m.group(2));
            return;
        }

        m = Pattern.compile("(?:make|give|grant|add) (\\S+)(?: as)? (?:a )?(?:committ?er|commit access) (?:of|on|to|at) (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            addGitHubCommitter(channel,sender,m.group(1),m.group(2));
            return;
        }

        m = Pattern.compile("(?:make|give|grant|add) (\\S+)(?: as)? (a )?(committ?er|commit access).*",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            addGitHubCommitter(channel,sender,m.group(1),null);
            return;
        }

        m = Pattern.compile("(?:create|make|add) (\\S+)(?: component)? in (?:the )?(?:issue|bug)(?: tracker| database)? for (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            createComponent(channel, sender, m.group(1), m.group(2));
            return;
        }
        
        m = Pattern.compile("(?:rem|remove|del|delete) component (\\S+) and move its issues to (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            deleteComponent(channel, sender, m.group(1), m.group(2));
            return;
        }

        m = Pattern.compile("rename component (\\S+) to (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            renameComponent(channel, sender, m.group(1), m.group(2));
            return;
        }

        m = Pattern.compile("(?:rem|remove) (?:the )?(?:lead|default assignee) (?:for|of|from) (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            removeDefaultAssignee(channel, sender, m.group(1));
            return;
        }
        
        m = Pattern.compile("(?:make|set) (\\S+) (?:the |as )?(?:lead|default assignee) (?:for|of) (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            setDefaultAssignee(channel, sender, m.group(2), m.group(1));
            return;
        }
        
        m = Pattern.compile("set (?:the )?description (?:for|of) (?:component )?(\\S+) to \\\"(.*)\\\"",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            setComponentDescription(channel, sender, m.group(1) , m.group(2));
            return;
        }
        
        m = Pattern.compile("(?:rem|remove) (?:the )?description (?:for|of) (?:component )?(\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            setComponentDescription(channel, sender, m.group(1) , null);
            return;
        }
        
        m = Pattern.compile("(?:make|give|grant|add) (\\S+) voice(?: on irc)?",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            grantAutoVoice(channel,sender,m.group(1));
            return;
        }

        m = Pattern.compile("(?:rem|remove|ungrant|del|delete) (\\S+) voice(?: on irc)?",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            removeAutoVoice(channel,sender,m.group(1));
            return;
        }

        m = Pattern.compile("(?:kick) (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            kickUser(channel,sender,m.group(1));
            return;
        }

        m = Pattern.compile("(?:host) (?:hosting-)(\\d+)((?:[ ]+)(\\S+))?", CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            String forkTo = "";
            if(m.groupCount() > 1) {
                forkTo = m.group(2);
            }
            setupHosting(channel,sender,m.group(1),forkTo);
            return;
        }

        if (payload.equalsIgnoreCase("version")) {
            version(channel);
            return;
        }

        if (payload.equalsIgnoreCase("help")) {
            help(channel);
            return;
        }

        if (payload.equalsIgnoreCase("refresh")) {
            // get the updated list
            channel.getBot().sendRaw().rawLineNow("NAMES " + channel);
            return;
        }
        
        if (payload.equalsIgnoreCase("restart")) {
            restart(channel,sender);
        }

        sendFallbackMessage(channel, payload, sender);

        try {
            PrintWriter w = new PrintWriter(new FileWriter(unknownCommands, true));
            w.println(payload);
            w.close();
        } catch (IOException e) {// if we fail to write, let it be.
            e.printStackTrace();
        }
    }

    private void sendFallbackMessage(Channel channel, String payload, User sender) {
        OutputChannel out = channel.send();
        if(StringUtils.containsIgnoreCase(payload, "thank")) {
            out.message("You're welcome");
        }
        else if(StringUtils.startsWithIgnoreCase(payload, "hello")) {
            out.message("Hello " + sender.getNick() + "!");
        }
        else if(random.nextInt(3)==0) {
            out.message("Wut?");
        }
        else {
            out.message("I didn't understand the command");
        }
    }

    /**
     * Restart ircbot.
     * 
     * We just need to quit, and docker container manager will automatically restart
     * another one. We've seen for some reasons sometimes jenkins-admin loses its +o flag,
     * and when that happens a restart fixes it quickly.
     */
    private void restart(Channel channel, User sender) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }
        
        channel.send().message("I'll quit and come back");
        System.exit(0);
    }

    private void kickUser(Channel channel, User sender, String target) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();
        for(User u : channel.getUsers()) {
            if(u.getNick().equalsIgnoreCase(target)) {
                out.kick(u, "kicked");
                out.message("Kicked user "+target);
                break;
            }
        }
    }

    private void setupHosting(Channel channel, User sender, String hostingId, String defaultForkTo) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();

        final String issueID = "HOSTING-" + hostingId;
        out.message("Approving hosting request " + issueID);
        replyBugStatus(channel, issueID);
        JiraRestClient client = null;

        try {
            client = JiraHelper.createJiraClient();
            final IssueRestClient issueClient = client.getIssueClient();
            final Issue issue = JiraHelper.getIssue(client, issueID);

            List<String> users = new ArrayList<String>();

            final com.atlassian.jira.rest.client.api.domain.User reporter = issue.getReporter();
            if (reporter == null) {
                // It should not be possible in Jenkins JIRA unless the unprobable user deletion case
                out.message("Warning: Default assignee is not specified in the reporter field. "
                        + "Component lead won't be set.");
            }
            String defaultAssignee = reporter != null ? reporter.getName() : "";

            String forkFrom = JiraHelper.getFieldValueOrDefault(issue, FORK_FROM_JIRA_FIELD, "");
            String userList = JiraHelper.getFieldValueOrDefault(issue, USER_LIST_JIRA_FIELD, "");
            for (String u : userList.split("\\n")) {
                if(StringUtils.isNotBlank(u))
                    users.add(u.trim());
            }
            String forkTo = JiraHelper.getFieldValueOrDefault(issue, FORK_TO_JIRA_FIELD, defaultForkTo);

            if(StringUtils.isBlank(forkFrom) || StringUtils.isBlank(forkTo) || users.isEmpty()) {
                out.message("Could not retrieve information (or information does not exist) from the HOSTING JIRA");
                return;
            }

            // Parse forkFrom in order to determine original repo owner and repo name
            Matcher m = Pattern.compile("(?:https:\\/\\/github\\.com/)?(\\S+)\\/(\\S+)",CASE_INSENSITIVE).matcher(forkFrom);
            if (m.matches()) {
                if(!forkGitHub(channel,sender,m.group(1),m.group(2),forkTo)) {
                    out.message("Hosting request failed to fork repository on Github");
                    return;
                }
            } else {
                out.message("ERROR: Cannot parse the source repo: " + forkFrom);
                return;
            }

            // add the users to the repo
            for(String user : users) {
                if(StringUtils.isNotBlank(user) && !addGitHubCommitter(channel,sender,user,forkTo)) {
                    out.message("Hosting request failed to add "+user+" as committer, continuing anyway");
                }
            }

            // create the JIRA component
            if(!createComponent(channel,sender,forkTo,defaultAssignee)) {
                out.message("Hosting request failed to create component "+forkTo+" in JIRA");
                return;
            }

            // update the issue with information on next steps
            String msg = "At the request of " + sender.getNick() + " on IRC, "
                    + "the code has been forked into the jenkinsci project on GitHub as "
                    + "https://github.com/jenkinsci/" + forkTo
                    + "\n\nA JIRA component named " + forkTo + " has also been created with "
                    + defaultAssignee + " as the default assignee for issues."
                    + "\n\nPlease remove your original repository so that the jenkinsci repository "
                    + "is the definitive source for the code. Also, please make sure you have "
                    + "a wiki page set up with the following guidelines in mind: \n\n"
                    + "* [Creating a wiki page|https://wiki.jenkins-ci.org/display/JENKINS/Hosting+Plugins#HostingPlugins-CreatingaWikipage]\n"
                    + "* [Reference the wiki page from your plugin|https://wiki.jenkins-ci.org/display/JENKINS/Hosting+Plugins#HostingPlugins-AddingyourWikipagetoyourrepo]\n"
                    + "\n\nYou will also need to do the following in order to push changes and release your plugin: \n\n"
                    + "* [Accept the invitation to the Jenkins CI Org on Github|https://github.com/jenkinsci]\n"
                    + "* [Request upload permission|https://wiki.jenkins-ci.org/display/JENKINS/Hosting+Plugins#HostingPlugins-Requestuploadpermissions]\n"
                    + "* [Releasing your plugin|https://wiki.jenkins-ci.org/display/JENKINS/Hosting+Plugins#HostingPlugins-Releasingtojenkins-ci.org]\n"
                    + "\n\nIn order for your plugin to be built by the [Jenkins CI Infrastructure|https://ci.jenkins.io] and check pull requests,"
                    + " please add a [Jenkinsfile|https://jenkins.io/doc/book/pipeline/jenkinsfile/] to the root of your repository with the following content:\n"
                    + "{code}\nbuildPlugin()\n{code}"
                    + "\n\nWelcome aboard!";

            // add comment
            issueClient.addComment(new URI(issue.getSelf().toString() + "/comment"), Comment.valueOf(msg)).
                    get(IrcBotConfig.JIRA_TIMEOUT_SEC, TimeUnit.SECONDS);

            try {
                Transition transition = JiraHelper.getTransitionByName(JiraHelper.getTransitions(issue), DONE_JIRA_RESOLUTION_NAME);
                if(transition != null) {
                    Collection<FieldInput> inputs = Arrays.asList(new FieldInput("resolution", ComplexIssueInputFieldValue.with("name", DONE_JIRA_RESOLUTION_NAME)));
                    JiraHelper.wait(issueClient.transition(issue, new TransitionInput(transition.getId(), inputs)));
                } else {
                    out.message("Unable to transition issue to \"" + DONE_JIRA_RESOLUTION_NAME + "\" state");
                }
            } catch (RestClientException e) {
                // if the issue cannot be put into the "resolved" state
                // (perhaps it's already in that state), let it be. Or else
                // we end up with the carpet bombing like HUDSON-2552.
                // See HUDSON-5133 for the failure mode.
                
                System.err.println("Failed to mark the issue as Done. " + e.getMessage());
                e.printStackTrace();
            }

            out.message("Hosting setup complete");
        } catch(Exception e) {
            e.printStackTrace();
            out.message("Failed setting up hosting for HOSTING-" + hostingId + ". " + e.getMessage());
        } finally {
            if(!JiraHelper.close(client)) {
                out.message("Failed to close JIRA client, possible leaked file descriptors");
            }
        }
    }

    private void replyBugStatus(Channel channel, String ticket) {
        Long time = recentIssues.get(ticket);

        recentIssues.put(ticket,System.currentTimeMillis());

        if (time!=null) {
            if (System.currentTimeMillis()-time < 60*1000) {
                return; // already mentioned recently. don't repeat
            }
        }

        try {
            channel.send().message(JiraHelper.getSummary(ticket));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    

    /**
     * Is the sender respected in the channel?
     *
     * IOW, does he have a voice of a channel operator?
     */
    private boolean isSenderAuthorized(Channel channel, User sender) {
        return isSenderAuthorized(channel, sender, true);
    }
    
    private boolean isSenderAuthorized(Channel channel, User sender, boolean acceptVoice) {
        return sender.getUserLevels(channel).stream().anyMatch(e -> e == UserLevel.OP || (acceptVoice && e == UserLevel.VOICE)
                        || (IrcBotConfig.TEST_SUPERUSER != null && IrcBotConfig.TEST_SUPERUSER.equals(sender.getNick()) ));
    }

    private void help(Channel channel) {
        channel.send().message("See https://jenkins.io/projects/infrastructure/ircbot/");
    }

    private void version(Channel channel) {
        OutputChannel out = channel.send();
        try {
            IrcBotBuildInfo buildInfo = IrcBotBuildInfo.readResourceFile("/versionInfo.properties");
            out.message("My version is "+buildInfo);
            out.message("Build URL: "+buildInfo.getBuildURL());
        } catch (IOException e) {
            e.printStackTrace();
            out.message("I don't know who I am");
        } 
    }

    private void insufficientPermissionError(Channel channel) {
        insufficientPermissionError(channel, true);
    }
    
    private void insufficientPermissionError(Channel channel, boolean acceptVoice ) {
        OutputChannel out = channel.send();
        final String requiredPrefix = acceptVoice ? "+ or @" : "@";
        out.message("Only people with "+requiredPrefix+" can run this command.");
        // I noticed that sometimes the bot just get out of sync, so ask the sender to retry
        channel.getBot().sendRaw().rawLineNow("NAMES "+channel);
        out.message("I'll refresh the member list, so if you think this is an error, try again in a few seconds.");
    }

    /**
     * Creates an issue tracker component.
     */
    private boolean createComponent(Channel channel, User sender, String subcomponent, String owner) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return false;
        }

        OutputChannel out = channel.send();

        out.message(String.format("Adding a new JIRA subcomponent %s to the %s project, owned by %s",
                subcomponent, IrcBotConfig.JIRA_DEFAULT_PROJECT, owner));

        boolean result = false;
        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            final ComponentRestClient componentClient = client.getComponentClient();
            final Promise<Component> createComponent = componentClient.createComponent(IrcBotConfig.JIRA_DEFAULT_PROJECT, 
                    new ComponentInput(subcomponent, "subcomponent", owner, AssigneeType.COMPONENT_LEAD));
            final Component component = JiraHelper.wait(createComponent);
            component.getSelf();
            out.message("New component created. URL is " + component.getSelf().toURL());
            result = true;
        } catch (Exception e) {
            out.message("Failed to create a new component: "+e.getMessage());
            e.printStackTrace();
        } finally {
            if(!JiraHelper.close(client)) {
                out.message("Failed to close JIRA client, possible leaked file descriptors");
            }
        }

        return result;
    }

    /**
     * Renames an issue tracker component.
     */
    private void renameComponent(Channel channel, User sender, String oldName, String newName) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();
        out.message(String.format("Renaming subcomponent %s to %s", oldName, newName));

        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            final Component component = JiraHelper.getComponent(client, IrcBotConfig.JIRA_DEFAULT_PROJECT, oldName);
            final ComponentRestClient componentClient = JiraHelper.createJiraClient().getComponentClient();
            Promise<Component> updateComponent = componentClient.updateComponent(component.getSelf(), 
                    new ComponentInput(newName, null, null, null));
            JiraHelper.wait(updateComponent);
            out.message("The component has been renamed");
        } catch (Exception e) {
            out.message(e.getMessage());
            e.printStackTrace();
        } finally {
            if(!JiraHelper.close(client)) {
                out.message("Failed to close JIRA client, possible leaked file descriptors");
            }
        }
    }
    
    /**
     * Deletes an issue tracker component.
     */
    private void deleteComponent(Channel channel, User sender, String deletedComponent, String backupComponent) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();

        out.message(String.format("Deleting the subcomponent %s. All issues will be moved to %s", deletedComponent, backupComponent));

        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            final Component component = JiraHelper.getComponent(client, IrcBotConfig.JIRA_DEFAULT_PROJECT, deletedComponent);
            final Component componentBackup = JiraHelper.getComponent(client, IrcBotConfig.JIRA_DEFAULT_PROJECT, backupComponent);
            Promise<Void> removeComponent = client.getComponentClient().removeComponent(component.getSelf(), componentBackup.getSelf());
            JiraHelper.wait(removeComponent);
            out.message("The component has been deleted");
        } catch (Exception e) {
            out.message(e.getMessage());
            e.printStackTrace();
        } finally {
            if(!JiraHelper.close(client)) {
                out.message("Failed to close JIRA client, possible leaked file descriptors");
            }
        }
    }
    
    /**
     * Deletes an assignee from the specified component
     */
    private void removeDefaultAssignee(Channel channel, User sender, String subcomponent) {
        setDefaultAssignee(channel, sender, subcomponent, null);
    }
    
    /**
     * Creates an issue tracker component.
     * @param owner User ID or null if the owner should be removed 
     */
    private void setDefaultAssignee(Channel channel, User sender, String subcomponent, @CheckForNull String owner) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();
        out.message(String.format("Changing default assignee of subcomponent %s to %s",subcomponent,owner));

        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            final Component component = JiraHelper.getComponent(client, IrcBotConfig.JIRA_DEFAULT_PROJECT, subcomponent);
            Promise<Component> updateComponent = client.getComponentClient().updateComponent(component.getSelf(), 
                    new ComponentInput(null, null, owner != null ? owner : "", AssigneeType.COMPONENT_LEAD));
            JiraHelper.wait(updateComponent);
            out.message(owner != null ? "Default assignee set to " + owner : "Default assignee has been removed");
        } catch (Exception e) {
            out.message("Failed to set default assignee: "+e.getMessage());
            e.printStackTrace();
        } finally {
            if(!JiraHelper.close(client)) {
                out.message("Failed to close JIRA client, possible leaked file descriptors");
            }
        }
    }
    
    /**
     * Sets the component description.
     * @param description Component description. Use null to remove the description
     */
    private void setComponentDescription(Channel channel, User sender, String componentName, @CheckForNull String description) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();

        out.message(String.format("Updating the description of component %s", componentName));

        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            final Component component = JiraHelper.getComponent(client, IrcBotConfig.JIRA_DEFAULT_PROJECT, componentName);
            Promise<Component> updateComponent = client.getComponentClient().updateComponent(component.getSelf(),
                    new ComponentInput(null, description != null ? description : "", null, null));
            JiraHelper.wait(updateComponent);
            out.message("The component description has been " + (description != null ? "updated" : "removed"));
        } catch (Exception e) {
            out.message(e.getMessage());
            e.printStackTrace();
        } finally {
            if(!JiraHelper.close(client)) {
                out.message("Failed to close JIRA client, possible leaked file descriptors");
            }
        }
    }
    
    private void grantAutoVoice(Channel channel, User sender, String target) {
        if (!isSenderAuthorized(channel,sender)) {
          insufficientPermissionError(channel);
          return;
        }

        OutputIRC out = channel.getBot().sendIRC();
        out.message("CHANSERV", "flags " + channel + " " + target + " +V");
        out.message("CHANSERV", "voice " + channel + " " + target);
        channel.send().message("Voice privilege (+V) added for " + target);
    }

    private void removeAutoVoice(Channel channel, User sender, String target) {
        if (!isSenderAuthorized(channel, sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputIRC out = channel.getBot().sendIRC();
        out.message("CHANSERV", "flags " + channel + " " + target + " -V");
        out.message("CHANSERV", "devoice " + channel + " " + target);
        channel.send().message("Voice privilege (-V) removed for " + target);
    }

    private void createGitHubRepository(Channel channel, User sender, String name, String collaborator) {
        OutputChannel out = channel.send();
        try {
            if (!isSenderAuthorized(channel,sender)) {
                insufficientPermissionError(channel);
                return;
            }

            GitHub github = GitHub.connect();
            GHOrganization org = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);
            GHRepository r = org.createRepository(name).private_(false).create();
            setupRepository(r);

            GHTeam t = getOrCreateRepoLocalTeam(org, r);
            if (collaborator!=null)
                t.add(github.getUser(collaborator));

            out.message("New github repository created at "+r.getUrl());
        } catch (IOException e) {
            out.message("Failed to create a repository: "+e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Adds a new collaborator to existing repositories.
     *
     * @param justForThisRepo
     *      Null to add to add the default team ("Everyone"), otherwise add him to a team specific repository.
     */
    private boolean addGitHubCommitter(Channel channel, User sender, String collaborator, String justForThisRepo) {
        boolean result = false;
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return false;
        }
        OutputChannel out = channel.send();
        try {
            if (justForThisRepo == null) {
                // legacy command
                out.message("I'm not longer managing the Everyone team. Please add committers to specific repos.");
                return false;
            }
            GitHub github = GitHub.connect();
            GHUser c = github.getUser(collaborator);
            GHOrganization o = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);
            
            final GHTeam t;
                GHRepository forThisRepo = o.getRepository(justForThisRepo);
                 if (forThisRepo == null) {
                     out.message("Could not find repository:  "+justForThisRepo);
                     return false;
                 }
                 t = getOrCreateRepoLocalTeam(o, forThisRepo);
                
            if (t==null) {
                out.message("No team for "+justForThisRepo);
                return false;
            }

            t.add(c);
            String successMsg = "Added "+collaborator+" as a GitHub committer for repository " + justForThisRepo;
            out.message(successMsg);
            result = true;
        } catch (IOException e) {
            out.message("Failed to add user to team: "+e.getMessage());
            e.printStackTrace();
        }
        return result;
    }

    private void renameGitHubRepo(Channel channel, User sender, String repo, String newName) {
        OutputChannel out = channel.send();
        try {
            if (!isSenderAuthorized(channel, sender, false)) {
                insufficientPermissionError(channel, false);
                return;
            }

            out.message("Renaming " + repo + " to " + newName);

            GitHub github = GitHub.connect();
            GHOrganization o = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);

            GHRepository orig = o.getRepository(repo);
            if (orig == null) {
                out.message("No such repository: " + repo);
                return;
            }

            orig.renameTo(newName);
            out.message("The repository has been renamed: https://github.com/" + IrcBotConfig.GITHUB_ORGANIZATION+"/"+newName);
        } catch (IOException e) {
            out.message("Failed to rename a repository: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * @param newName
     *      If not null, rename a repository after a fork.
     */
    boolean forkGitHub(Channel channel, User sender, String owner, String repo, String newName) {
        boolean result = false;
        OutputChannel out = channel.send();
        try {
            if (!isSenderAuthorized(channel,sender)) {
                insufficientPermissionError(channel);
                return false;
            }

            GitHub github = GitHub.connect();
            GHOrganization org = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);
            GHRepository check = org.getRepository(newName);
            if(check != null) {
                out.message("Repository with name "+newName+" already exists in "+IrcBotConfig.GITHUB_ORGANIZATION);
                return false;
            }

            // check if there is an existing real (not-renamed) repository with the name
            // if a repo has been forked and renamed, we can clone as that name and be fine
            // we just want to make sure we don't fork to an current repository name.
            check = org.getRepository(repo);
            if(check != null && check.getName().equalsIgnoreCase(repo)) {
                out.message("Repository " + repo + " can't be forked, an existing repository with that name already exists in " + IrcBotConfig.GITHUB_ORGANIZATION);
                return false;
            }

            out.message("Forking "+repo);

            GHUser user = github.getUser(owner);
            if (user==null) {
                out.message("No such user: "+owner);
                return false;
            }
            GHRepository orig = user.getRepository(repo);
            if (orig==null) {
                out.message("No such repository: "+repo);
                return false;
            }

            GHRepository r;
            try {
                r = orig.forkTo(org);
            } catch (IOException e) {
                // we started seeing 500 errors, presumably due to time out.
                // give it a bit of time, and see if the repository is there
                System.out.println("GitHub reported that it failed to fork "+owner+"/"+repo+". But we aren't trusting");
                r = null;
                for (int i=0; r==null && i<5; i++) {
                    Thread.sleep(1000);
                    r = org.getRepository(repo);
                }
                if (r==null)
                    throw e;
            }
            if (newName!=null) {
                r.renameTo(newName);

                r = null;
                for (int i=0; r==null && i<5; i++) {
                    Thread.sleep(1000);
                    r = org.getRepository(newName);
                }
                if (r==null)
                    throw new IOException(repo+" renamed to "+newName+" but not finding the new repository");
            }

            // GitHub adds a lot of teams to this repo by default, which we don't want
            Set<GHTeam> legacyTeams = r.getTeams();

            GHTeam t = getOrCreateRepoLocalTeam(org, r);
            try {
                t.add(user);    // the user immediately joins this team
                
            } catch (IOException e) {
                // if 'user' is an org, the above command would fail
                out.message("Failed to add "+user+" to the new repository. Maybe an org?: "+e.getMessage());
                // fall through
            }


            setupRepository(r);

            out.message("Created https://github.com/" + IrcBotConfig.GITHUB_ORGANIZATION + "/" + (newName != null ? newName : repo));

            // remove all the existing teams
            for (GHTeam team : legacyTeams)
                team.remove(r);

            result = true;
        } catch (InterruptedException e) {
            out.message("Failed to fork a repository: "+e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            out.message("Failed to fork a repository: "+e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Fix up the repository set up to our policy.
     */
    private void setupRepository(GHRepository r) throws IOException {
        r.enableIssueTracker(false);
        r.enableWiki(false);
    }

    /**
     * Creates a repository local team, and grants access to the repository.
     */
    private GHTeam getOrCreateRepoLocalTeam(GHOrganization org, GHRepository r) throws IOException {
        String teamName = r.getName() + " Developers";
        GHTeam t = org.getTeams().get(teamName);
        if (t==null) {
            t = org.createTeam(teamName, Permission.PULL, r);
            t.add(r, Permission.ADMIN); // make team an admin on the given repository
        } else {
            if (!t.getRepositories().containsValue(r)) {
                t.add(r);
            }
        }
        return t;
    }

    public static void main(String[] args) throws Exception {
        Configuration.Builder builder = new Configuration.Builder()
                .setName(IrcBotConfig.NAME)
                .setServer(IrcBotConfig.SERVER, 6667)
                .setAutoReconnect(true)
                .addListener(new IrcListener(new File("unknown-commands.txt")));

        for(String channel : IrcBotConfig.getChannels()) {
            builder.addAutoJoinChannel(channel);
        }

        if(args.length>0) {
            builder.setCapEnabled(true)
                   .addCapHandler(new SASLCapHandler(IrcBotConfig.NAME, args[0]));
        }

        System.out.println("Connecting to "+IrcBotConfig.SERVER+" as "+IrcBotConfig.NAME);
        System.out.println("GitHub organization = "+IrcBotConfig.GITHUB_ORGANIZATION);

        PircBotX bot = new PircBotX(builder.buildConfiguration());
        bot.startBot();
    }

    static {
        /*
            I started seeing the SSL related problem. Given that there's no change in project_tools.html
            that include this Google Analytics, I'm not really sure why this is happening.
            Until I sort it out, I'm just dumbing down HTTPS into HTTP. Given that this only runs in one place
            and its network is well protected, I don't think this enables attacks to the system.


1270050442632 ### java.lang.RuntimeException: Error loading included script: java.io.IOException: HTTPS hostname wrong:  should be <ssl.google-analytics.com>
1270050442632 ###       at com.meterware.httpunit.ParsedHTML.getScript(ParsedHTML.java:291)
1270050442632 ###       at com.meterware.httpunit.ParsedHTML.interpretScriptElement(ParsedHTML.java:269)
1270050442632 ###       at com.meterware.httpunit.ParsedHTML.access$600(ParsedHTML.java:37)
1270050442632 ###       at com.meterware.httpunit.ParsedHTML$ScriptFactory.recordElement(ParsedHTML.java:404)
1270050442632 ###       at com.meterware.httpunit.ParsedHTML$2.processElement(ParsedHTML.java:556)
1270050442632 ###       at com.meterware.httpunit.NodeUtils$PreOrderTraversal.perform(NodeUtils.java:169)
1270050442632 ###       at com.meterware.httpunit.ParsedHTML.loadElements(ParsedHTML.java:566)
1270050442632 ###       at com.meterware.httpunit.ParsedHTML.getForms(ParsedHTML.java:101)
1270050442632 ###       at com.meterware.httpunit.WebResponse.getForms(WebResponse.java:311)
1270050442632 ###       at org.kohsuke.jnt.JNMembership.grantRole(JNMembership.java:200)
1270050442632 ###       at org.jvnet.hudson.backend.ircbot.IrcBotImpl.grantCommitAccess(IrcBotImpl.java:181)
1270050442632 ###       at org.jvnet.hudson.backend.ircbot.IrcBotImpl.onMessage(IrcBotImpl.java:74)
1270050442632 ###       at org.jibble.pircbot.PircBot.handleLine(PircBot.java:927)
1270050442633 ###       at org.jibble.pircbot.InputThread.run(InputThread.java:95)
         */
        class TrustAllManager implements X509TrustManager {
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(X509Certificate[] x509Certificates, String s) {
            }

            public void checkServerTrusted(X509Certificate[] x509Certificates, String s) {
            }
        }

        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, new TrustManager[]{new TrustAllManager()}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (GeneralSecurityException e) {
            throw new Error(e);
        }
    }
}
