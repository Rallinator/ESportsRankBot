To run the bot, you need the following:
- a discord bot token. put in Environment variable DISCORD_BOT_TOKEN
- for development, a development discord bot token. put in Environment variable DEV_BOT_TOKEN
- a mongodb database. put the connection string in applications.properties, spring.data.mongodb.uri
- put the discord id of the bot owner in applications.properties, bot.owner-id
- for development, put the ids of your test servers in applications.properties, elorankingbot.test-server-ids
- not sure if ente2-id or announcement-channel-id are still used
- use Maven to build the project: mvn -B package -DskipTests --file backend/pom.xml
- get the bot invite link from the discord developer portal and invite it to your server
- run the bot with java -XX:MaxRAM=200m --add-opens java.base/java.lang=ALL-UNNAMED -jar -Dserver.port= elotracking.jar
- some of the exceptions sent to the owner are false positives, for example the webhook errors don't make apparent sense to me
