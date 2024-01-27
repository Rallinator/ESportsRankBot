package com.elorankingbot.commands.admin;

import com.elorankingbot.FormatTools;
import com.elorankingbot.command.annotations.AdminCommand;
import com.elorankingbot.command.annotations.GlobalCommand;
import com.elorankingbot.commands.SlashCommand;
import com.elorankingbot.model.Game;
import com.elorankingbot.service.Services;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.command.ApplicationCommandInteractionOption;
import discord4j.core.object.command.ApplicationCommandOption;
import discord4j.core.object.entity.Role;
import discord4j.discordjson.json.ApplicationCommandOptionChoiceData;
import discord4j.discordjson.json.ApplicationCommandOptionData;
import discord4j.discordjson.json.ApplicationCommandRequest;
import lombok.extern.apachecommons.CommonsLog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static discord4j.core.object.command.ApplicationCommandOption.Type.STRING;

@AdminCommand
@GlobalCommand
@CommonsLog
public class CreateRanking extends SlashCommand {

	private final List<Long> testServerIds;

	public CreateRanking(ChatInputInteractionEvent event, Services services) {
		super(event, services);
		this.testServerIds = services.props.getTestServerIds();
	}

	public static ApplicationCommandRequest getRequest() {
		return ApplicationCommandRequest.builder()
				.name(CreateRanking.class.getSimpleName().toLowerCase())
				.description("Create a ranking")
				.defaultPermission(true)
				.addOption(ApplicationCommandOptionData.builder()
						.name("nameofranking").description("What do you call this ranking?")
						.type(ApplicationCommandOption.Type.STRING.getValue())
						.required(true).build())
				.addOption(ApplicationCommandOptionData.builder()
						.name("allowdraw").description("Allow draw results and not just win or lose?")
						.type(STRING.getValue())
						.addChoice(ApplicationCommandOptionChoiceData.builder()
								.name("allow draws").value("allow").build())
						.addChoice(ApplicationCommandOptionChoiceData.builder()
								.name("no draws").value("nodraw").build())
						.required(true).build())
				.addOption(ApplicationCommandOptionData.builder()
						.name("role").description("Set a role that is required to be able to join the queue")
						.type(ApplicationCommandOption.Type.ROLE.getValue())
						.required(false).build())
				.build();
	}


	public static String getShortDescription() {
		return "Create a new ranking on the server.";
	}

	public static String getLongDescription() {
		return getShortDescription() + "\n" +
				"`Required:` `nameofranking` The name of the new ranking.\n" +
				"`Required:` `allowdraw` Whether or not to allow draw as a result in matches for this ranking.\n" +
				"`Not Required:` `role` If a certain role is required to join the queue.\n" +
				"For more information on rankings, see `/help:` `Concept: Rankings and Queues`.";
	}

	protected void execute() {
		String nameOfGame = event.getOption("nameofranking").get().getValue().get().asString();

		Optional<ApplicationCommandInteractionOption> roleCommand = event.getOption("role");
		long roleId = 0;

		if(roleCommand.isPresent()){
			Role role = roleCommand.get().getValue().get().asRole().block();
			roleId = role.getId().asLong();
		}

		if (!FormatTools.isLegalDiscordName(nameOfGame)) {
			event.reply(FormatTools.illegalNameMessage())
					.subscribe(NO_OP, super::forwardToExceptionHandler);
			return;
		}
		if (server.getGames().contains(new Game(server, nameOfGame, false, roleId))) {
			event.reply("A ranking of that name already exists.").subscribe();
			return;
		}

		event.deferReply().subscribe();

		boolean allowDraw = event.getOption("allowdraw").get().getValue().get().asString().equals("allow");
		Game game = new Game(server, nameOfGame, allowDraw, roleId);// TODO duplikate verhindern
		server.addGame(game);
		channelManager.getOrCreateResultChannel(game);
		channelManager.refreshLeaderboard(game);
		channelManager.getOrCreateMatchCategory(server);
		channelManager.getOrCreateDisputeCategory(server);
		channelManager.getOrCreateArchiveCategory(server);
		dbService.saveServer(server);

		String updatedCommands = discordCommandManager.updateGameCommands(server, exceptionHandler.updateCommandFailedCallbackFactory(event));

		boolean didCreateCategories = server.getDisputeCategoryId() == 0L;
		event.editReply(String.format("Ranking %s has been created. I also created <#%s> where I will post all match results%s" +
								"<#%s> where I put the leaderboard%s." +
								"\nHowever, there is no way yet for players to find a match. " +
								"Use `/addqueue` to either add a queue the ranking." +
								"\nThese commands have been updated: %s",
						nameOfGame,
						game.getResultChannelId(),
						didCreateCategories ? ", " : " and ",
						game.getLeaderboardChannelId(),
						didCreateCategories ? ", and channel categories for disputes and an archive" : "",
						updatedCommands))
				.subscribe(NO_OP, super::forwardToExceptionHandler);
		if (!testServerIds.contains(server.getGuildId())) {
			bot.sendToOwner(String.format("Created ranking %s on %s : %s",
					nameOfGame, guildId, event.getInteraction().getGuild().block().getName()));
		}
	}
}
