package org.jenkinsci.backend.ircbot;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.regex.Pattern.CASE_INSENSITIVE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import io.github.ma1uta.matrix.client.model.sync.SyncResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.CheckForNull;

import org.jenkinsci.backend.ircbot.fallback.BotsnackMessage;
import org.jenkinsci.backend.ircbot.fallback.FallbackMessage;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHOrganization.Permission;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;
import org.kohsuke.github.GHTeamBuilder;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.jira.rest.client.api.ComponentRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.AssigneeType;
import com.atlassian.jira.rest.client.api.domain.Component;
import com.atlassian.jira.rest.client.api.domain.input.ComponentInput;
import com.google.common.collect.ImmutableSortedSet;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.atlassian.util.concurrent.Promise;
import io.github.ma1uta.matrix.client.StandaloneClient;
import io.github.ma1uta.matrix.client.model.sync.AccountData;
import io.github.ma1uta.matrix.client.model.sync.InvitedRoom;
import io.github.ma1uta.matrix.client.model.sync.JoinedRoom;
import io.github.ma1uta.matrix.client.model.sync.LeftRoom;
import io.github.ma1uta.matrix.client.model.sync.Presence;
import io.github.ma1uta.matrix.client.model.sync.Rooms;
import io.github.ma1uta.matrix.client.model.sync.Timeline;
import io.github.ma1uta.matrix.client.sync.SyncLoop;
import io.github.ma1uta.matrix.client.sync.SyncParams;
import io.github.ma1uta.matrix.event.Event;
import io.github.ma1uta.matrix.event.RoomEvent;
import io.github.ma1uta.matrix.event.StateEvent;
import io.github.ma1uta.matrix.event.content.EventContent;
import io.github.ma1uta.matrix.event.content.RoomMessageContent;

/**

* IRC Bot on irc.libera.chat as a means to delegate administrative work to committers.
*
* @author Kohsuke Kawaguchi
 */
public class IrcListener {

    public static class Channel {
        StandaloneClient mxClient;
        String roomId;

        Channel(StandaloneClient mxClient, String roomId) {
            this.mxClient = mxClient;
            this.roomId = roomId;
        }

        OutputChannel send() {
            return new OutputChannel(this.mxClient, this.roomId);
        }

    }

    public static enum UserLevel {
        VOICE,
        OP
    }

    public static class User {
        String userId;

        User(String userId) {
            this.userId = userId;
        }

        String getUserId() {
            return this.userId;
        }

        ImmutableSortedSet<UserLevel> getUserLevels(Channel channel) {
            return ImmutableSortedSet.of(UserLevel.OP);
        }
    }


    public static class OutputChannel {
        String roomId;
        StandaloneClient mxClient;

        OutputChannel(StandaloneClient mxClient, String roomId) {
            this.mxClient = mxClient;
            this.roomId = roomId;
        }

        void message(String msg) {
            System.out.println("roomId: " + roomId + ", msg: " + msg);
            mxClient.event().sendMessage(roomId, msg);
        }
    }

    public static class MessageEvent {
        String message = "";
        String roomId = "";
        String senderId = "";

        StandaloneClient mxClient;

        public MessageEvent(StandaloneClient mxClient, String roomId, String message, String senderId) {
            this.roomId = roomId;
            this.message = message;
            this.mxClient = mxClient;
            this.senderId = senderId;
        }

        Channel getChannel() {
            return new Channel(this.mxClient, this.roomId);
        }

        User getUser() {
            return new User(this.senderId);
        }

        String getMessage() {
            return this.message;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(IrcListener.class);

    private final StandaloneClient mxClient;
    private final String botUserId;

    public IrcListener(StandaloneClient mxClient, String botUserId) {
        this.mxClient = mxClient;
        this.botUserId = botUserId;
    }

    public void onMessage(MessageEvent e) {
        final Channel channel = e.getChannel();
        final User sender = e.getUser();
        final String message = e.getMessage();
        final String directMessagePrefix = "jenkins-admin:";

        try {
            if (message.startsWith(directMessagePrefix)) { // Direct command to the bot
                // remove prefixes, trim whitespaces
                String payload = message.substring(directMessagePrefix.length()).trim();
                payload = payload.replaceAll("\\s+", " ");
                handleDirectCommand(channel, sender, payload);
            }
        } catch (RuntimeException ex) { // Catch unhandled runtime issues
            ex.printStackTrace();
            channel.send().message("An error ocurred. Please submit a ticket to the Jenkins infra helpdesk with the following exception:");
            channel.send().message("https://github.com/jenkins-infra/helpdesk/issues/new?assignees=&labels=triage,irc&template=1-report-issue.yml");
            channel.send().message(ex.getMessage());
            throw ex; // Propagate the error to the caller in order to let it log and handle the issue
        }
    }

    /**
     * Handles direct commands coming to the bot.
     * The handler presumes the external trimming of the payload.
     */
    void handleDirectCommand(Channel channel, User sender, String payload) {
        Matcher m;

        m = Pattern.compile("(?:create|make|add) (\\S+)(?: repository)? (?:on|in) github(?: for (\\S+))?(?: with (jira|github issues))?",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            createGitHubRepository(channel,sender,m.group(1),m.group(2),m.group(3) != null && m.group(3).toLowerCase().contains("github"));
            return;
        }

        m = Pattern.compile("fork (?:<https://github\\.com/)?(\\S+)/(\\S+)(>?: on github)?(?: as (\\S+))?(?: with (jira|github issues))?",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            forkGitHub(channel,sender,m.group(1),m.group(2),m.group(3), emptyList(), m.group(4).toLowerCase().contains("github"));
            return;
        }

        m = Pattern.compile("rename (?:github )repo (\\S+) to (\\S+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            renameGitHubRepo(channel,sender,m.group(1),m.group(2));
            return;
        }

        m = Pattern.compile("(?:make|give|grant|add) (\\S+)(?: as)? (?:a )?(?:committ?er|commit access) (?:of|on|to|at) (.+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            List<String> repos = collectGroups(m, 2);
            addGitHubCommitter(channel,sender,m.group(1),repos);
            return;
        }

        m = Pattern.compile("(?:remove|revoke) (\\S+)(?: as)? (?:a )?(committ?er|member) (?:from|on) (.+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            List<String> repos = collectGroups(m, 2);
            removeGitHubCommitter(channel,sender,m.group(1),repos);
            return;
        }

        m = Pattern.compile("(?:make|give|grant|add) (\\S+)(?: as)? (?:a )?(?:maintainer) on (.+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            List<String> repos = collectGroups(m, 2);
            makeGitHubTeamMaintainer(channel, sender, m.group(1), repos);
            return;
        }

        m = Pattern.compile("(?:make) (.*) team(?:s)? visible",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            List<String> teams = collectGroups(m, 1);
            makeGitHubTeamVisible(channel, sender, teams);
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

        m = Pattern.compile("(?:rem|remove) (?:the )?(?:lead|default assignee) (?:for|of|from) (.+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            List<String> subcomponents = collectGroups(m,1);
            removeDefaultAssignee(channel, sender, subcomponents);
            return;
        }

        m = Pattern.compile("(?:make|set) (\\S+) (?:the |as )?(?:lead|default assignee) (?:for|of) (.+)",CASE_INSENSITIVE).matcher(payload);
        if (m.matches()) {
            List<String> subcomponents = collectGroups(m, 2);
            setDefaultAssignee(channel, sender, subcomponents, m.group(1));
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
            // FIXME - channel.getBot().sendRaw().rawLineNow("NAMES " + channel);
            return;
        }

        if(payload.equalsIgnoreCase("botsnack")) {
            sendBotsnackMessage(channel, sender);
            return;
        }

        if (payload.equalsIgnoreCase("restart")) {
            restart(channel,sender);
        }

        sendFallbackMessage(channel, payload, sender);
    }

    private void sendBotsnackMessage(Channel channel, User sender) {
        channel.send().message((new BotsnackMessage().answer()));
    }

    private void sendFallbackMessage(Channel channel, String payload, User sender) {
        channel.send().message(new FallbackMessage(payload, sender.getUserId()).answer());
    }

    /**
     * Restart ircbot.
     *
     * We just need to quit, and docker container manager will automatically restart
     * another one. We've seen for some reasons sometimes jenkins-admin loses its +o flag,
     * and when that happens a restart fixes it quickly.
     */
    @SuppressFBWarnings(
            value="DM_EXIT",
            justification="Intentionally restarting the app"
    )
    private void restart(Channel channel, User sender) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }

        channel.send().message("I'll quit and come back");
        System.exit(0);
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
        return (IrcBotConfig.TEST_SUPERUSER != null && IrcBotConfig.TEST_SUPERUSER.equals(sender.getUserId()))
                || sender.getUserLevels(channel).stream().anyMatch(e -> e == UserLevel.OP
                || (acceptVoice && e == UserLevel.VOICE));
    }

    private void help(Channel channel) {
        channel.send().message("See <https://jenkins.io/projects/infrastructure/ircbot/>");
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
        // FIXME - channel.getBot().sendRaw().rawLineNow("NAMES "+channel);
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
    private void removeDefaultAssignee(Channel channel, User sender, List<String> subcomponents) {
        setDefaultAssignee(channel, sender, subcomponents, null);
    }

    /**
  * Creates an issue tracker component.
  * @param owner User ID or null if the owner should be removed
     */
    private void setDefaultAssignee(Channel channel, User sender, List<String> subcomponents,
                                    @CheckForNull String owner) {
        if (!isSenderAuthorized(channel, sender)) {
            insufficientPermissionError(channel);
            return;
        }

        OutputChannel out = channel.send();
        JiraRestClient client = null;
        try {
            client = JiraHelper.createJiraClient();
            for (String subcomponent : subcomponents) {
                try {
                    out.message(String.format("Changing default assignee of subcomponent %s to %s", subcomponent, owner));
                    final Component component = JiraHelper.getComponent(client, IrcBotConfig.JIRA_DEFAULT_PROJECT, subcomponent);
                    Promise<Component> updateComponent = client.getComponentClient().updateComponent(component.getSelf(),
                            new ComponentInput(null, null, owner != null ? owner : "", AssigneeType.COMPONENT_LEAD));
                    JiraHelper.wait(updateComponent);
                    out.message(owner != null ? String.format("Default assignee set to %s for %s", owner, subcomponent)
                            : "Default assignee has been removed for " + subcomponent);
                } catch(ExecutionException | TimeoutException | InterruptedException | IOException e) {
                    out.message(String.format("Failed to set default assignee for %s: %s", subcomponent, e.getMessage()));
                    e.printStackTrace();
                }
            }
        } catch (Throwable e) {
            out.message("Failed to connect to Jira: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (!JiraHelper.close(client)) {
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

    private void createGitHubRepository(Channel channel, User sender, String name, String collaborator, boolean useGHIssues) {
        OutputChannel out = channel.send();
        try {
            if (!isSenderAuthorized(channel,sender)) {
                insufficientPermissionError(channel);
                return;
            }

            GitHub github = GitHub.connect();
            GHOrganization org = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);
            GHRepository r = org.createRepository(name).private_(false).create();
            setupRepository(r, useGHIssues);

            getOrCreateRepoLocalTeam(out, github, org, r, singletonList(collaborator));

            out.message("New github repository created at "+r.getUrl());
        } catch (IOException e) {
            out.message("Failed to create a repository: "+e.getMessage());
            e.printStackTrace();
        }
    }

    /**
  * Makes GitHub team visible.
  *
  * @param teams
  * teams to make visible
     */
    private void makeGitHubTeamVisible(Channel channel, User sender, List<String> teams) {
        if (!isSenderAuthorized(channel, sender)) {
            insufficientPermissionError(channel);
            return;
        }
        OutputChannel out = channel.send();
        try {
            GitHub github = GitHub.connect();
            GHOrganization o = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);

            for (String team : teams) {
                try {
                    final GHTeam ghTeam = o.getTeamByName(team);
                    if (ghTeam == null) {
                        out.message("No team for " + team);
                        continue;
                    }

                    ghTeam.setPrivacy(GHTeam.Privacy.CLOSED);

                    out.message("Made GitHub team " + team + " visible");
                } catch (IOException e) {
                    out.message("Failed to make GitHub team " + team + " visible: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch(IOException e) {
            out.message("Failed to connect to GitHub or retrieve organization information: " + e.getMessage());
        }
    }

    /**
  * Makes a user a maintainer of a GitHub team
  *
  * @param teams
  * make user a maintainer of one oe more teams.
     */
    private void makeGitHubTeamMaintainer(Channel channel, User sender, String newTeamMaintainer, List<String> teams) {
        if (!isSenderAuthorized(channel, sender)) {
            insufficientPermissionError(channel);
            return;
        }
        OutputChannel out = channel.send();
        try {
            GitHub github = GitHub.connect();
            GHOrganization o = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);
            for (String team : teams) {
                try {
                    GHUser c = github.getUser(newTeamMaintainer);

                    final GHTeam ghTeam = o.getTeamByName(team);
                    if (ghTeam == null) {
                        out.message("No team for " + team);
                        continue;
                    }

                    ghTeam.add(c, GHTeam.Role.MAINTAINER);
                    out.message("Added " + newTeamMaintainer + " as a GitHub maintainer for team " + team);
                } catch (IOException e) {
                    out.message("Failed to make user maintainer of one or more teams: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            out.message("Failed to connect to GitHub or get organization information: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
  * Adds a new collaborator to existing repositories.
  *
  * @param repos
  * List of repositories to add the collaborator to.
     */
    private void addGitHubCommitter(Channel channel, User sender, String collaborator, List<String> repos) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }
        OutputChannel out = channel.send();

        if (repos == null || repos.isEmpty()) {
            // legacy command
            out.message("I'm not longer managing the Everyone team. Please add committers to specific repos.");
            return;
        }

        try {
            GitHub github = GitHub.connect();
            GHUser c = github.getUser(collaborator);
            GHOrganization o = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);

            for(String repo : repos) {
                try {
                    final GHTeam t;
                    GHRepository forThisRepo = o.getRepository(repo);
                    if (forThisRepo == null) {
                        out.message("Could not find repository:  " + repo);
                        continue;
                    }

                    t = getOrCreateRepoLocalTeam(out, github, o, forThisRepo, emptyList());
                    t.add(c);
                    out.message(String.format("Added %s as a GitHub committer for repository %s", collaborator, repo));
                } catch(IOException e) {
                    out.message("Failed to add user to team: "+e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            out.message("Failed to connect to GitHub or get organization/user information: "+e.getMessage());
            e.printStackTrace();
        }
    }

    private void removeGitHubCommitter(Channel channel, User sender, String collaborator, List<String> repos) {
        if (!isSenderAuthorized(channel,sender)) {
            insufficientPermissionError(channel);
            return;
        }
        OutputChannel out = channel.send();
        try {
            GitHub github = GitHub.connect();
            GHUser githubUser = github.getUser(collaborator);
            GHOrganization githubOrganization = github.getOrganization(IrcBotConfig.GITHUB_ORGANIZATION);
            for(String repo : repos) {
                try {
                    final GHTeam ghTeam;
                    GHRepository forThisRepo = githubOrganization.getRepository(repo);
                    if (forThisRepo == null) {
                        out.message("Could not find repository:  " + repo);
                        continue;
                    }

                    ghTeam = getOrCreateRepoLocalTeam(out, github, githubOrganization, forThisRepo, emptyList());
                    ghTeam.remove(githubUser);
                    out.message("Removed " + collaborator + " as a GitHub committer for repository " + repo);
                } catch(IOException e) {
                    out.message("Failed to remove user from team: "+e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            out.message("Failed to connect to GitHub or retrieve organization or user information: "+e.getMessage());
            e.printStackTrace();
        }
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
  * If not null, rename a repository after a fork.
     */
    boolean forkGitHub(Channel channel, User sender, String owner, String repo, String newName, List<String> maintainers, boolean useGHIssues) {
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
                LOGGER.warn("GitHub reported that it failed to fork {}/{}. But we aren't trusting", owner, repo);
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

            try {
                getOrCreateRepoLocalTeam(out, github, org, r, maintainers.isEmpty() ? singletonList(user.getName()) : maintainers);
            } catch (IOException e) {
                // if 'user' is an org, the above command would fail
                out.message("Failed to add "+user+" to the new repository. Maybe an org?: "+e.getMessage());
                // fall through
            }
            setupRepository(r, useGHIssues);

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
    private static void setupRepository(GHRepository r, boolean useGHIssues) throws IOException {
        r.enableIssueTracker(useGHIssues);
        r.enableWiki(false);
    }

    /**
  * Creates a repository local team, and grants access to the repository.
     */
    private static GHTeam getOrCreateRepoLocalTeam(OutputChannel out, GitHub github, GHOrganization org, GHRepository r, List<String> githubUsers) throws IOException {
        String teamName = r.getName() + " Developers";
        GHTeam t = org.getTeams().get(teamName);
        if (t == null) {
            GHTeamBuilder ghCreateTeamBuilder = org.createTeam(teamName).privacy(GHTeam.Privacy.CLOSED);
            List<String> maintainers = emptyList();
            if (!githubUsers.isEmpty()) {
                maintainers = githubUsers.stream()
                        // in order to be added as a maintainer of a team you have to be a member of the org already
                        .filter(user -> isMemberOfOrg(github, org, user))
                        .collect(Collectors.toList());
                ghCreateTeamBuilder = ghCreateTeamBuilder.maintainers(maintainers.toArray(new String[0]));
            }
            t = ghCreateTeamBuilder.create();

            List<String> usersNotInMaintainers = new ArrayList<>(githubUsers);
            usersNotInMaintainers.removeAll(maintainers);
            final GHTeam team = t;
            usersNotInMaintainers.forEach(addUserToTeam(out, github, team));
            // github automatically adds the user to the team who created the team, we don't want that
            team.remove(github.getMyself());
        }

        t.add(r, Permission.ADMIN); // make team an admin on the given repository, always do in case the config is wrong
        return t;
    }

    private static Consumer<String> addUserToTeam(OutputChannel out, GitHub github, GHTeam team) {
        return user -> {
            try {
                team.add(github.getUser(user));
            } catch (IOException e) {
                out.message(String.format("Failed to add user %s to team %s, error was:  %s", user, team.getName(), e.getMessage()));
                e.printStackTrace();
            }
        };
    }

    private static boolean isMemberOfOrg(GitHub gitHub, GHOrganization org, String user) {
        try {
            GHUser ghUser = gitHub.getUser(user);
            return org.hasMember(ghUser);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static List<String> collectGroups(Matcher m, int startingGroup) {
        List<String> items = new ArrayList<>(
                Arrays.asList(m.group(startingGroup).split("\\s*,\\s*")));

        return items;
    }

    public static void printEvent(Event<?> event) {
        System.out.println("Type: " + event.getType());

        if (event instanceof RoomEvent) {
            RoomEvent<?> roomEvent = (RoomEvent<?>) event;

            System.out.println("Event ID: " + roomEvent.getEventId());
            System.out.println("Room ID: " + roomEvent.getRoomId());
            System.out.println("Sender: " + roomEvent.getSender());
            System.out.println("Origin server TS: " + roomEvent.getOriginServerTs());

            if (roomEvent instanceof StateEvent) {
                StateEvent<?> stateEvent = (StateEvent<?>) roomEvent;

                System.out.println("State key: " + stateEvent.getStateKey());
            }
        }

        EventContent content = event.getContent();
        if (content instanceof RoomMessageContent) {
            RoomMessageContent roomMessageContent = (RoomMessageContent) content;

            System.out.println("MSG type: " + roomMessageContent.getMsgtype());
            System.out.println("Body: " + roomMessageContent.getBody());
        }
    }

    private Boolean firstSync = true;
    public void processIncomingEvents(SyncResponse syncResponse, SyncParams syncParams) {
            Boolean wasFirstSync = this.firstSync;
            this.firstSync = false;

            // Sender: @jenkins-hosting:matrix.org
            System.out.println("Next batch: " + syncParams.getNextBatch());
            if (syncParams.isFullState()) {
                syncParams.setFullState(false);
            }

            this.firstSync = false;

            // inbound listener to parse incoming events.
            AccountData accountData = syncResponse.getAccountData();
            if (accountData != null) {
                System.out.println("=== Account data ===");
                accountData.getEvents().forEach(IrcListener::printEvent);
            }
            Presence presence = syncResponse.getPresence();
            if (presence != null) {
                presence.getEvents().forEach(IrcListener::printEvent);
            }
            Rooms rooms = syncResponse.getRooms();
            if (rooms != null) {
                //Map<String, InvitedRoom> invite = rooms.getInvite();
                //if (invite != null) {
                //    System.out.println("=== Invites ===");
                //    for (Map.Entry<String, InvitedRoom> inviteEntry : invite.entrySet()) {
                //        System.out.println("Invite into the room: " + inviteEntry.getKey());
                //        InvitedRoom invitedRoom = inviteEntry.getValue();
                //        if (invitedRoom != null && invitedRoom.getInviteState() != null) {
                //            List<Event> inviteEvents = invitedRoom.getInviteState().getEvents();
                //            if (inviteEvents != null) {
                //                inviteEvents.forEach(IrcListener::printEvent);
                //            }
                //        }
                //    }
                //}

                //Map<String, LeftRoom> leave = rooms.getLeave();
                //if (leave != null) {
                //    System.out.println("=== Left rooms ===");
                //    for (Map.Entry<String, LeftRoom> leftEntry : leave.entrySet()) {
                //        System.out.println("Left from the room: " + leftEntry.getKey());
                //    }
                //}

                Map<String, JoinedRoom> join = rooms.getJoin();
                if (join != null) {
                    //System.out.println("=== Joined rooms ===");
                    for (Map.Entry<String, JoinedRoom> joinEntry : join.entrySet()) {
                        JoinedRoom joinedRoom = joinEntry.getValue();

                        if (joinedRoom.getState() != null) {
                            System.out.println("= State =");
                            if (joinedRoom.getState().getEvents() != null) {
                                joinedRoom.getState().getEvents().forEach(IrcListener::printEvent);
                            }
                        }

                        Timeline timeline = joinedRoom.getTimeline();
                        if (timeline != null && timeline.getEvents() != null) {
                            System.out.println("= Timeline =");
                            // FIXME - here
                            // timeline.getEvents().forEach(IrcListener::printEvent);
                            for (Event<?> event : timeline.getEvents()) {
                                if (event.getType() == "m.room.message") {
                                    String senderId = ((RoomEvent<?>) event).getSender();
                                    // skip own messages
                                    if (senderId.equals(botUserId)) {
                                        continue;
                                    }
                                    // ((RoomEvent<?>) event).getEventId()

                                    EventContent content = event.getContent();
                                    if (content instanceof RoomMessageContent) {
                                        RoomMessageContent roomMessageContent = (RoomMessageContent) content;
                                        if (!wasFirstSync && roomMessageContent.getMsgtype() == "m.text") {
                                            MessageEvent me = new MessageEvent(
                                                    mxClient,
                                                    joinEntry.getKey(),
                                                    roomMessageContent.getBody().trim(),
                                                    senderId
                                            );
                                            this.onMessage(me);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // syncParams.setTerminate(true); // to stop SyncLoop.
    }

    public static void main(String[] args) throws Exception {
        LOGGER.info("Connecting to {} as {}.", IrcBotConfig.SERVER, IrcBotConfig.NAME);
        LOGGER.info("GitHub organization: {}", IrcBotConfig.GITHUB_ORGANIZATION);

        StandaloneClient mxClient = new StandaloneClient.Builder().domain(System.getenv("MATRIX_SERVER")).build();
        // login
        String botUserId = mxClient.auth().login(System.getenv("MATRIX_LOGIN"), System.getenv("MATRIX_PASSWORD").toCharArray()).getUserId();

        // set display name via profile api
        mxClient.profile().setDisplayName("Jenkins-Hosting");

        IrcListener il = new IrcListener(mxClient, botUserId);
        SyncLoop syncLoop = new SyncLoop(mxClient.sync(), il::processIncomingEvents);

        // Set initial parameters (Optional).
        SyncParams params = SyncParams.builder()
            .fullState(false)
            .timeout(10 * 1000L).build();

        syncLoop.setInit(params);

        // run the syncLoop
        ExecutorService service = Executors.newFixedThreadPool(1);
        service.execute(syncLoop);
    }
}

