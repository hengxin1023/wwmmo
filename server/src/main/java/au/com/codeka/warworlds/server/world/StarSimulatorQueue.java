package au.com.codeka.warworlds.server.world;

import au.com.codeka.warworlds.common.Log;
import au.com.codeka.warworlds.common.Time;
import au.com.codeka.warworlds.common.proto.Star;
import au.com.codeka.warworlds.common.sim.Simulation;
import au.com.codeka.warworlds.server.store.DataStore;
import au.com.codeka.warworlds.server.store.StarQueueSecondaryStore;

/**
 * This class manages the star simulation queue, and schedules stars to be simulated at the
 * appropriate time.
 */
public class StarSimulatorQueue {
  public static final StarSimulatorQueue i = new StarSimulatorQueue();
  private static final Log log = new Log("StarSimulatorQueue");

  private final Thread thread;
  private final StarQueueSecondaryStore queue;
  private boolean running;
  private final Object pinger = new Object();

  private StarSimulatorQueue() {
    queue = DataStore.i.starsQueue();
    thread = new Thread(this::run, "StarSimulateQueue");
  }

  public void start() {
    log.info("Starting star simulation queue.");
    running = true;
    thread.start();
  }

  public void stop() {
    running = false;
    ping();
    try {
      thread.join();
    } catch (InterruptedException e) {
      // Ignore.
    }
  }

  public void ping() {
    synchronized (pinger) {
      pinger.notify();
    }
  }

  private void run() {
    log.info("Star simulator queue starting up.");
    while (running) {
      Star star = queue.next(null);

      long waitTime;
      if (star == null) {
        log.warning("No stars to simulate, sleeping for a bit.");
        waitTime = 10 * Time.MINUTE;
      } else {
        if (star.next_simulation == null) {
          log.warning("Star #%d (%s) next_simulation is null.", star.id, star.name);
          waitTime = 0;
        } else {
          waitTime = star.next_simulation - System.currentTimeMillis();
        }
      }

      if (waitTime <= 0) {
        waitTime = 1; // 1 millisecond to ensure that we actually sleep at least a litte.
      }

      // Don't sleep for more than 10 minutes, we'll just loop around and check again.
      if (waitTime > 10 * Time.MINUTE) {
        waitTime = 10 * Time.MINUTE;
      }

      log.debug("Star simulator sleeping for %d ms.", waitTime);
      try {
        synchronized (pinger) {
          pinger.wait(waitTime);
        }
      } catch (InterruptedException e) {
        // Ignore.
      }

      if (star != null) {
        long startTime = System.nanoTime();
        WatchableObject<Star> watchableStar = StarManager.i.getStar(star.id);
        Star.Builder starBuilder = watchableStar.get().newBuilder();
        new Simulation().simulate(starBuilder);
        StarManager.i.completeActions(watchableStar, starBuilder);
        long endTime = System.nanoTime();

        log.info("Star #%d (%s) simulated in %dms",
            star.id, star.name, (endTime - startTime) / 1000000L);
      }
    }

    log.info("Star simulator queue shut down.");
  }
}
