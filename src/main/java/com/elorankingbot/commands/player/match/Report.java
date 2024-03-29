package com.elorankingbot.commands.player.match;

import com.elorankingbot.components.EmbedBuilder;
import com.elorankingbot.model.Match;
import com.elorankingbot.model.MatchResult;
import com.elorankingbot.model.ReportStatus;
import com.elorankingbot.service.MatchService;
import com.elorankingbot.service.Services;
import com.elorankingbot.timedtask.DurationParser;
import discord4j.core.event.domain.interaction.ButtonInteractionEvent;

import java.util.Date;
import java.util.List;

import static com.elorankingbot.timedtask.TimedTask.TimedTaskType.MATCH_AUTO_RESOLVE;
import static com.elorankingbot.timedtask.TimedTask.TimedTaskType.MATCH_WARN_MISSING_REPORTS;

public abstract class Report extends ButtonCommandRelatedToMatch {

	private final ReportStatus reportStatus;
	private final List<Long> testServerIds;

	public Report(ButtonInteractionEvent event, Services services, ReportStatus reportStatus) {
		super(event, services);
		this.reportStatus = reportStatus;
		this.testServerIds = services.props.getTestServerIds();
	}

	public void execute() {
		if (!activeUserIsInvolvedInMatch() || match.isDispute()) {
			acknowledgeEvent();
			return;
		}
		long timePassed = new Date().getTime() - match.getTimestamp().getTime();
		if (!this.getClass().equals(Cancel.class) && timePassed < 5*60*1000 && !testServerIds.contains(server.getGuildId())) {
			event.reply(String.format("Please wait another %s before making a report.",
							DurationParser.minutesToString((int) Math.ceil(5 - timePassed / (60*1000)))))
					.withEphemeral(true).subscribe();
			return;
		}

		acknowledgeEvent();
		match.reportAndSetConflictData(activePlayerId, reportStatus);
		Match.ReportIntegrity reportIntegrity = match.getReportIntegrity();
		switch (reportIntegrity) {
			case INCOMPLETE -> {
				processIncompleteReporting();
				if (!match.isHasFirstReport()) {
					timedTaskScheduler.addTimedTask(MATCH_WARN_MISSING_REPORTS, 50, 0L, 0L, match.getId());
					timedTaskScheduler.addTimedTask(MATCH_AUTO_RESOLVE, 60, 0L, 0L, match.getId());
					match.setHasFirstReport(true);
				}
				dbService.saveMatch(match);
			}
			case CONFLICT -> {
				processConflictingReporting();
				dbService.saveMatch(match);
			}
			case CANCEL -> {
				MatchResult canceledMatchResult = MatchService.generateCanceledMatchResult(match);
				matchService.processMatchResult(canceledMatchResult, match, "The match has been canceled.", manageRoleFailedCallbackFactory());
			}
			case COMPLETE -> {
				MatchResult matchResult = MatchService.generateMatchResult(match);
				String resolveMessage = "The match has been resolved. Below are your new ratings and the rating changes.";
				matchService.processMatchResult(matchResult, match, resolveMessage, manageRoleFailedCallbackFactory());
			}
		}
	}

	private void processIncompleteReporting() {
		String title = "Not all players have reported yet. " +
				"Please report the result of the match, if you haven't already.";
		// TODO maybe this needs to get through MatchService instead of editing the message directly
		bot.getMessage(match.getMessageId(), match.getChannelId()).subscribe(message -> message
				.edit().withEmbeds(EmbedBuilder.createMatchEmbed(title, match))
				.subscribe());
	}

	private void processConflictingReporting() {
		String title = "There are conflicts. Please try to sort out the issue with the other players. " +
				"If you cannot find a solution, file a dispute.";
		bot.getMessage(match.getMessageId(), match.getChannelId()).subscribe(message -> message
				.edit().withEmbeds(EmbedBuilder.createMatchEmbed(title, match)).subscribe());
	}
}