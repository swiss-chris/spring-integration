/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.file;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.Lifecycle;
import org.springframework.integration.aggregator.ResequencingMessageGroupProcessor;
import org.springframework.integration.context.IntegrationObjectSupport;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.file.filters.AcceptOnceFileListFilter;
import org.springframework.integration.file.filters.FileListFilter;
import org.springframework.integration.file.filters.ResettableFileListFilter;
import org.springframework.lang.UsesJava7;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.util.Assert;

/**
 * {@link MessageSource} that creates messages from a file system directory.
 * To prevent messages for certain files, you may supply a {@link FileListFilter}.
 * By default, an {@link AcceptOnceFileListFilter} is used.
 * It ensures files are picked up only once from the directory.
 * <p>
 * A common problem with reading files is that a file may be detected before it
 * is ready. The default {@link AcceptOnceFileListFilter}
 * does not prevent this. In most cases, this can be prevented if the
 * file-writing process renames each file as soon as it is ready for reading. A
 * pattern-matching filter that accepts only files that are ready (e.g. based on
 * a known suffix), composed with the default {@link AcceptOnceFileListFilter}
 * would allow for this.
 * <p>
 * A {@link Comparator} can be used to ensure internal ordering of the Files in
 * a {@link PriorityBlockingQueue}. This does not provide the same guarantees as
 * a {@link ResequencingMessageGroupProcessor}, but in cases where writing files
 * and failure downstream are rare it might be sufficient.
 * <p>
 * FileReadingMessageSource is fully thread-safe under concurrent
 * <code>receive()</code> invocations and message delivery callbacks.
 *
 * @author Iwein Fuld
 * @author Mark Fisher
 * @author Oleg Zhurakousky
 * @author Gary Russell
 * @author Artem Bilan
 */
public class FileReadingMessageSource extends IntegrationObjectSupport implements MessageSource<File>, Lifecycle {

	private static final int DEFAULT_INTERNAL_QUEUE_CAPACITY = 5;

	private static final Log logger = LogFactory.getLog(FileReadingMessageSource.class);

	/*
	 * {@link PriorityBlockingQueue#iterator()} throws
	 * {@link java.util.ConcurrentModificationException} in Java 5.
	 * There is no locking around the queue, so there is also no iteration.
	 */
	private final Queue<File> toBeReceived;

	private volatile File directory;

	private volatile DirectoryScanner scanner = new DefaultDirectoryScanner();

	private volatile boolean scannerExplicitlySet;

	private volatile boolean autoCreateDirectory = true;

	private volatile boolean scanEachPoll = false;

	private volatile boolean running;

	private FileListFilter<File> filter;

	private FileLocker locker;

	private boolean useWatchService;

	private WatchEventType[] watchEvents = new WatchEventType[] { WatchEventType.CREATE };

	/**
	 * Creates a FileReadingMessageSource with a naturally ordered queue of unbounded capacity.
	 */
	public FileReadingMessageSource() {
		this(null);
	}

	/**
	 * Creates a FileReadingMessageSource with a bounded queue of the given
	 * capacity. This can be used to reduce the memory footprint of this
	 * component when reading from a large directory.
	 *
	 * @param internalQueueCapacity
	 *            the size of the queue used to cache files to be received
	 *            internally. This queue can be made larger to optimize the
	 *            directory scanning. With scanEachPoll set to false and the
	 *            queue to a large size, it will be filled once and then
	 *            completely emptied before a new directory listing is done.
	 *            This is particularly useful to reduce scans of large numbers
	 *            of files in a directory.
	 */
	public FileReadingMessageSource(int internalQueueCapacity) {
		this(null);
		Assert.isTrue(internalQueueCapacity > 0,
				"Cannot create a queue with non positive capacity");
		this.scanner = new HeadDirectoryScanner(internalQueueCapacity);
	}

	/**
	 * Creates a FileReadingMessageSource with a {@link PriorityBlockingQueue}
	 * ordered with the passed in {@link Comparator}
	 * <p>
	 * The size of the queue used should be large enough to hold all the files
	 * in the input directory in order to sort all of them, so restricting the
	 * size of the queue is mutually exclusive with ordering. No guarantees
	 * about file delivery order can be made under concurrent access.
	 * <p>
	 *
	 * @param receptionOrderComparator
	 *            the comparator to be used to order the files in the internal
	 *            queue
	 */
	public FileReadingMessageSource(Comparator<File> receptionOrderComparator) {
		this.toBeReceived = new PriorityBlockingQueue<File>(
				DEFAULT_INTERNAL_QUEUE_CAPACITY, receptionOrderComparator);
	}


	/**
	 * Specify the input directory.
	 *
	 * @param directory to monitor
	 */
	public void setDirectory(File directory) {
		Assert.notNull(directory, "directory must not be null");
		this.directory = directory;
	}

	/**
	 * Optionally specify a custom scanner, for example the
	 * {@link WatchServiceDirectoryScanner}
	 *
	 * @param scanner scanner implementation
	 */
	public void setScanner(DirectoryScanner scanner) {
		Assert.notNull(scanner, "'scanner' must not be null.");
		this.scanner = scanner;
		this.scannerExplicitlySet = true;
	}

	/**
	 * The {@link #scanner} property accessor to allow to modify its options
	 * ({@code filter}, {@code locker} etc.) at runtime using the
	 * {@link FileReadingMessageSource} bean.
	 * @return the {@link DirectoryScanner} of this {@link FileReadingMessageSource}.
	 * @since 4.2
	 */
	public DirectoryScanner getScanner() {
		return this.scanner;
	}

	/**
	 * Specify whether to create the source directory automatically if it does
	 * not yet exist upon initialization. By default, this value is
	 * <em>true</em>. If set to <em>false</em> and the
	 * source directory does not exist, an Exception will be thrown upon
	 * initialization.
	 *
	 * @param autoCreateDirectory
	 *            should the directory to be monitored be created when this
	 *            component starts up?
	 */
	public void setAutoCreateDirectory(boolean autoCreateDirectory) {
		this.autoCreateDirectory = autoCreateDirectory;
	}

	/**
	 * Sets a {@link FileListFilter}.
	 * By default a {@link org.springframework.integration.file.filters.AcceptOnceFileListFilter}
	 * with no bounds is used. In most cases a customized {@link FileListFilter} will
	 * be needed to deal with modification and duplication concerns.
	 * If multiple filters are required a
	 * {@link org.springframework.integration.file.filters.CompositeFileListFilter}
	 * can be used to group them together.
	 * <p>
	 * <b>The supplied filter must be thread safe.</b>.
	 * @param filter a filter
	 */
	public void setFilter(FileListFilter<File> filter) {
		Assert.notNull(filter, "'filter' must not be null");
		this.filter = filter;
	}

	/**
	 * Optional. Sets a {@link FileLocker} to be used to guard files against
	 * duplicate processing.
	 * <p>
	 * <b>The supplied FileLocker must be thread safe</b>
	 * @param locker a locker
	 */
	public void setLocker(FileLocker locker) {
		Assert.notNull(locker, "'fileLocker' must not be null.");
		this.locker = locker;
	}

	/**
	 * Optional. Set this flag if you want to make sure the internal queue is
	 * refreshed with the latest content of the input directory on each poll.
	 * <p>
	 * By default this implementation will empty its queue before looking at the
	 * directory again. In cases where order is relevant it is important to
	 * consider the effects of setting this flag. The internal
	 * {@link java.util.concurrent.BlockingQueue} that this class is keeping
	 * will more likely be out of sync with the file system if this flag is set
	 * to <code>false</code>, but it will change more often (causing expensive
	 * reordering) if it is set to <code>true</code>.
	 *
	 * @param scanEachPoll
	 *            whether or not the component should re-scan (as opposed to not
	 *            rescanning until the entire backlog has been delivered)
	 */
	public void setScanEachPoll(boolean scanEachPoll) {
		this.scanEachPoll = scanEachPoll;
	}

	/**
	 * Switch this {@link FileReadingMessageSource} to use its internal
	 * {@link FileReadingMessageSource.WatchServiceDirectoryScanner}.
	 * @param useWatchService the {@code boolean} flag to switch to
	 * {@link FileReadingMessageSource.WatchServiceDirectoryScanner} on {@code true}.
	 * @since 4.3
	 * @see #setWatchEvents
	 */
	public void setUseWatchService(boolean useWatchService) {
		this.useWatchService = useWatchService;
	}

	/**
	 * The {@link WatchService} event types.
	 * If {@link #setUseWatchService} isn't {@code true}, this option is ignored.
	 * @param watchEvents the set of {@link WatchEventType}.
	 * @since 4.3
	 * @see #setUseWatchService
	 */
	public void setWatchEvents(WatchEventType... watchEvents) {
		Assert.notEmpty(watchEvents, "'watchEvents' must not be empty.");
		Assert.noNullElements(watchEvents, "'watchEvents' must not contain null elements.");
		Assert.state(!this.running, "Cannot change watch events while running.");

		this.watchEvents = Arrays.copyOf(watchEvents, watchEvents.length);
	}

	@Override
	public String getComponentType() {
		return "file:inbound-channel-adapter";
	}

	@Override
	public void start() {
		if (this.scanner instanceof Lifecycle) {
			((Lifecycle) this.scanner).start();
		}
		this.running = true;
	}

	@Override
	public void stop() {
		if (this.scanner instanceof Lifecycle) {
			((Lifecycle) this.scanner).start();
		}
		this.running = false;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	protected void onInit() {
		Assert.notNull(this.directory, "'directory' must not be null");
		if (!this.directory.exists() && this.autoCreateDirectory) {
			this.directory.mkdirs();
		}
		Assert.isTrue(this.directory.exists(),
				"Source directory [" + this.directory + "] does not exist.");
		Assert.isTrue(this.directory.isDirectory(),
				"Source path [" + this.directory + "] does not point to a directory.");
		Assert.isTrue(this.directory.canRead(),
				"Source directory [" + this.directory + "] is not readable.");

		Assert.state(!(this.scannerExplicitlySet && this.useWatchService),
				"The 'scanner' and 'useWatchService' options are mutually exclusive: " + this.scanner);

		if (this.useWatchService) {
			this.scanner = new WatchServiceDirectoryScanner();
		}

		Assert.state(!(this.scannerExplicitlySet && (this.filter != null || this.locker != null)),
				"The 'filter' and 'locker' options must be present on the provided external 'scanner': "
						+ this.scanner);
		if (this.filter != null) {
			this.scanner.setFilter(this.filter);
		}
		if (this.locker != null) {
			this.scanner.setLocker(this.locker);
		}

	}

	@Override
	public Message<File> receive() throws MessagingException {
		Message<File> message = null;

		// rescan only if needed or explicitly configured
		if (this.scanEachPoll || this.toBeReceived.isEmpty()) {
			scanInputDirectory();
		}

		File file = this.toBeReceived.poll();

		// file == null means the queue was empty
		// we can't rely on isEmpty for concurrency reasons
		while ((file != null) && !this.scanner.tryClaim(file)) {
			file = this.toBeReceived.poll();
		}

		if (file != null) {
			message = this.getMessageBuilderFactory().withPayload(file).build();
			if (logger.isInfoEnabled()) {
				logger.info("Created message: [" + message + "]");
			}
		}
		return message;
	}

	private void scanInputDirectory() {
		List<File> filteredFiles = this.scanner.listFiles(this.directory);
		Set<File> freshFiles = new LinkedHashSet<File>(filteredFiles);
		if (!freshFiles.isEmpty()) {
			this.toBeReceived.addAll(freshFiles);
			if (logger.isDebugEnabled()) {
				logger.debug("Added to queue: " + freshFiles);
			}
		}
	}

	/**
	 * Adds the failed message back to the 'toBeReceived' queue if there is room.
	 * @param failedMessage the {@link Message} that failed
	 */
	public void onFailure(Message<File> failedMessage) {
		if (logger.isWarnEnabled()) {
			logger.warn("Failed to send: " + failedMessage);
		}
		this.toBeReceived.offer(failedMessage.getPayload());
	}

	/**
	 * The message is just logged. It was already removed from the queue during
	 * the call to <code>receive()</code>
	 * @param sentMessage the message that was successfully delivered
	 * @deprecated with no replacement. Redundant method.
	 */
	@Deprecated
	public void onSend(Message<File> sentMessage) {
		if (logger.isDebugEnabled()) {
			logger.debug("Sent: " + sentMessage);
		}
	}

	@UsesJava7
	public enum WatchEventType {

		CREATE(StandardWatchEventKinds.ENTRY_CREATE),

		MODIFY(StandardWatchEventKinds.ENTRY_MODIFY),

		DELETE(StandardWatchEventKinds.ENTRY_DELETE);

		private final WatchEvent.Kind<Path> kind;

		WatchEventType(WatchEvent.Kind<Path> kind) {
			this.kind = kind;
		}

	}

	@UsesJava7
	private class WatchServiceDirectoryScanner extends DefaultDirectoryScanner implements Lifecycle {

		private final ConcurrentMap<Path, WatchKey> pathKeys = new ConcurrentHashMap<Path, WatchKey>();

		private WatchService watcher;

		private Collection<File> initialFiles;

		private WatchEvent.Kind<?>[] kinds;

		@Override
		public void start() {
			try {
				this.watcher = FileSystems.getDefault().newWatchService();
			}
			catch (IOException e) {
				logger.error("Failed to create watcher for " + FileReadingMessageSource.this.directory, e);
			}

			this.kinds = new WatchEvent.Kind<?>[FileReadingMessageSource.this.watchEvents.length];

			for (int i = 0; i < FileReadingMessageSource.this.watchEvents.length; i++) {
				this.kinds[i] = FileReadingMessageSource.this.watchEvents[i].kind;
			}

			final Set<File> initialFiles = walkDirectory(FileReadingMessageSource.this.directory.toPath(), null);
			initialFiles.addAll(filesFromEvents());
			this.initialFiles = initialFiles;
		}

		@Override
		public void stop() {
			try {
				this.watcher.close();
				this.watcher = null;
			}
			catch (IOException e) {
				logger.error("Failed to close watcher for " + FileReadingMessageSource.this.directory, e);
			}
		}

		@Override
		public boolean isRunning() {
			return true;
		}

		@Override
		protected File[] listEligibleFiles(File directory) {
			Assert.state(this.watcher != null, "The WatchService has'nt been started");
			if (this.initialFiles != null) {
				File[] initial = this.initialFiles.toArray(new File[this.initialFiles.size()]);
				this.initialFiles = null;
				return initial;
			}
			Collection<File> files = filesFromEvents();
			return files.toArray(new File[files.size()]);
		}

		private Set<File> filesFromEvents() {
			WatchKey key = this.watcher.poll();
			Set<File> files = new LinkedHashSet<File>();
			while (key != null) {
				File parentDir = ((Path) key.watchable()).toAbsolutePath().toFile();
				for (WatchEvent<?> event : key.pollEvents()) {
					if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
							event.kind() == StandardWatchEventKinds.ENTRY_MODIFY ||
							event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
						Path item = (Path) event.context();
						File file = new File(parentDir, item.toFile().getName());
						if (logger.isDebugEnabled()) {
							logger.debug("Watch event [" + event.kind() + "] for file [" + file + "]");
						}

						if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
							if (FileReadingMessageSource.this.filter instanceof ResettableFileListFilter) {
								((ResettableFileListFilter<File>) FileReadingMessageSource.this.filter).remove(file);
							}
							boolean fileRemoved = files.remove(file);
							if (fileRemoved && logger.isDebugEnabled()) {
								logger.debug("The file [" + file +
										"] has been removed from the queue because of DELETE event.");
							}
						}
						else {
							if (file.exists()) {
								if (file.isDirectory()) {
									files.addAll(walkDirectory(file.toPath(), event.kind()));
								}
								else {
									files.remove(file);
									files.add(file);
								}
							}
							else {
								if (logger.isDebugEnabled()) {
									logger.debug("A file [" + file + "] for the event [" + event.kind() +
											"] doesn't exist. Ignored.");
								}
							}
						}
					}
					else if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
						if (logger.isDebugEnabled()) {
							logger.debug("Watch event [" + StandardWatchEventKinds.OVERFLOW +
									"] with context [" + event.context() + "]");
						}

						for (WatchKey watchKey : this.pathKeys.values()) {
							watchKey.cancel();
						}
						this.pathKeys.clear();

						if (event.context() != null && event.context() instanceof Path) {
							files.addAll(walkDirectory((Path) event.context(), event.kind()));
						}
						else {
							files.addAll(walkDirectory(FileReadingMessageSource.this.directory.toPath(), event.kind()));
						}
					}
				}
				key.reset();
				key = this.watcher.poll();
			}
			return files;
		}

		private Set<File> walkDirectory(Path directory, final WatchEvent.Kind<?> kind) {
			final Set<File> walkedFiles = new LinkedHashSet<File>();
			try {
				registerWatch(directory);
				Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
						FileVisitResult fileVisitResult = super.preVisitDirectory(dir, attrs);
						registerWatch(dir);
						return fileVisitResult;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						FileVisitResult fileVisitResult = super.visitFile(file, attrs);
						if (!StandardWatchEventKinds.ENTRY_MODIFY.equals(kind)) {
							walkedFiles.add(file.toFile());
						}
						return fileVisitResult;
					}

				});
			}
			catch (IOException e) {
				logger.error("Failed to walk directory: " + directory.toString(), e);
			}
			return walkedFiles;
		}

		private void registerWatch(Path dir) throws IOException {
			if (!this.pathKeys.containsKey(dir)) {
				if (logger.isDebugEnabled()) {
					logger.debug("registering: " + dir + " for file events");
				}
				WatchKey watchKey = dir.register(this.watcher, this.kinds);
				this.pathKeys.putIfAbsent(dir, watchKey);
			}
		}

	}

}
