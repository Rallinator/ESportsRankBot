package com.elorankingbot.timedtask;

import com.elorankingbot.commands.timed.AutoResolveMatch;
import com.elorankingbot.commands.timed.DecayAcceptedChallenge;
import com.elorankingbot.commands.timed.DecayOpenChallenge;
import com.elorankingbot.dao.TimeSlotDao;
import com.elorankingbot.dao.TimedTaskQueueCurrentIndexDao;
import com.elorankingbot.logging.ExceptionHandler;
import com.elorankingbot.model.CurrentIndex;
import com.elorankingbot.model.TimeSlot;
import com.elorankingbot.service.DBService;
import com.elorankingbot.service.DiscordBotService;
import com.elorankingbot.service.Services;
import lombok.Getter;
import lombok.extern.apachecommons.CommonsLog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

@CommonsLog
@Component
public class TimedTaskScheduler {

    @Getter
    private final int numberOfTimeSlots;
    @Getter
    private int currentIndex;
    private final Services services;
    private final DBService dbService;
    private final DiscordBotService bot;
    private final TimedTaskService timedTaskService;
    private final ExceptionHandler exceptionHandler;
    private final TimeSlotDao timeSlotDao;
    private final TimedTaskQueueCurrentIndexDao timedTaskQueueCurrentIndexDao;
    private final boolean doRunSchedulers;

    public TimedTaskScheduler(Services services,
                              TimeSlotDao timeSlotDao, TimedTaskQueueCurrentIndexDao timedTaskQueueCurrentIndexDao) {
        this.services = services;
        this.dbService = services.dbService;
        this.bot = services.bot;
        this.timedTaskService = services.timedTaskService;
        this.exceptionHandler = services.exceptionHandler;
        this.timeSlotDao = timeSlotDao;
        this.timedTaskQueueCurrentIndexDao = timedTaskQueueCurrentIndexDao;
        this.numberOfTimeSlots = services.props.getNumberOfTimeSlots();
        this.doRunSchedulers = services.props.isDoRunSchedulers();

        if (!timedTaskQueueCurrentIndexDao.existsById(1)) {
            currentIndex = 0;
        } else {
            currentIndex = timedTaskQueueCurrentIndexDao.findById(1).get().getValue();
        }
    }

    public void addTimedTask(TimedTask.TimedTaskType type, int duration, long relationId, long otherId, Object value) {
        if (!doRunSchedulers) return;

        int targetTimeSlotIndex = (currentIndex + duration) % numberOfTimeSlots;
        log.debug(String.format("adding timed task for %s of type %s with timer %s to slot %s",
                relationId, type.name(), duration, targetTimeSlotIndex));
        Optional<TimeSlot> maybeTimeSlot = timeSlotDao.findById(targetTimeSlotIndex);
        Set<TimedTask> timedTasks = maybeTimeSlot.isPresent() ? maybeTimeSlot.get().getTimedTasks() : new HashSet<>();
        timedTasks.add(new TimedTask(type, duration, relationId, otherId, value));
        timeSlotDao.save(new TimeSlot(targetTimeSlotIndex, timedTasks));
    }

    private void processTimedTask(TimedTask task) {
        long id = task.relationId();
        long otherId = task.otherId();
        int duration = task.duration();
        log.debug(String.format("executing %s %s after %s", task.type().name(), id, duration));
        switch (task.type()) {
            case OPEN_CHALLENGE_DECAY -> new DecayOpenChallenge(services, id, duration).execute();
            case ACCEPTED_CHALLENGE_DECAY -> new DecayAcceptedChallenge(services, id, duration).execute();
            case MATCH_WARN_MISSING_REPORTS -> timedTaskService.warnMissingReports((UUID) task.value());
            case MATCH_AUTO_RESOLVE -> new AutoResolveMatch(services, (UUID) task.value(), duration).execute();
            case MATCH_SUMMARIZE -> timedTaskService.summarizeMatch(id, otherId, task.value());
            case MESSAGE_DELETE -> timedTaskService.deleteMessage(id, otherId);
            case CHANNEL_DELETE -> timedTaskService.deleteChannel(id);
            case PLAYER_UNBAN -> timedTaskService.unbanPlayer(id, otherId, duration);
            case LEAVE_QUEUES -> timedTaskService.leaveQueues(id, otherId, duration, task.value());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void tick() {
        try {
            if (!doRunSchedulers) return;

            log.debug("tick " + currentIndex);
            if (currentIndex % (7 * 24 * 60) == 0) {
                List<Long> allGuildIds = bot.getAllGuildIds();
                timedTaskService.unmarkServersForDeletionIfAgainPresent(allGuildIds);
                timedTaskService.deleteServersMarkedForDeletion();
                timedTaskService.markServersForDeletion(allGuildIds);
            }
            if (currentIndex % (24 * 60) == 0) {
                dbService.persistBotStatsAndRestartAccumulator();
            }

            Optional<TimeSlot> maybeTimeSlot = timeSlotDao.findById(currentIndex);
            if (maybeTimeSlot.isPresent()) {
                for (TimedTask task : maybeTimeSlot.get().getTimedTasks()) {
                    try {
                        processTimedTask(task);
                    } catch (Exception e) {
                        bot.sendToOwner(String.format("Error in TimedTaskQueue::tick\n%s", e.getMessage()));
                        e.printStackTrace();
                    }
                }
                timeSlotDao.delete(maybeTimeSlot.get());
            }

            currentIndex++;
            if (currentIndex >= numberOfTimeSlots) currentIndex = 0;
            timedTaskQueueCurrentIndexDao.save(new CurrentIndex(currentIndex));
        } catch (Exception e) {
            exceptionHandler.handleException(e, this.getClass().getSimpleName() + "::tick");
        }
    }

    public int getRemainingDuration(int index) {
        return index > currentIndex ?
                index - currentIndex
                : numberOfTimeSlots + index - currentIndex;
    }
}
