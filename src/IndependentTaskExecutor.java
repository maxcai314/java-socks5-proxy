import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Function;

import static java.lang.System.Logger;
import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.WARNING;

/**
 * A {@link StructuredTaskScope} that does not shut down the executor if a task fails.
 */
public class IndependentTaskExecutor<E extends Exception> extends StructuredTaskScope<Void> {
	private final Logger logger;

	public IndependentTaskExecutor(String name, Logger logger) {
		super(name, Thread.ofVirtual().factory());
		this.logger = logger;
	}

	@Override
	protected void handleComplete(Subtask<? extends Void> subtask) {
		super.handleComplete(subtask);

		if (subtask.state() == Subtask.State.FAILED) {
			logger.log(WARNING, "Connection Lost", subtask.exception());
		}
	}

	public void submit(String taskName, InterruptibleRunnable<E> task) {
		fork(() -> {
			Thread.currentThread().setName(taskName);
			task.run();

			return null;
		});
	}

	public interface InterruptibleRunnable<E extends Exception> {
		void run() throws E, InterruptedException;
	}
}