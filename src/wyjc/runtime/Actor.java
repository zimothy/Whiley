// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
// * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
// * Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
// * Neither the name of the <organization> nor the
// names of its contributors may be used to endorse or promote products
// derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyjc.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A Whiley ref, which mirrors an actor in the Actor Model of concurrency.
 * 
 * @author David Pearce
 * @author Timothy Jones
 */
public final class Actor extends Continuation implements Runnable {

	private final Scheduler scheduler;

	private Message currentMessage = null;
	private Message finalMessage = null;

	private int mailboxSize = 0;
	private final int mailboxLimit = 10;

	private Object state;

	/**
	 * Whether the messager should resume immediately upon completing its yielding
	 * process. This is used mainly to react to a premature resumption, when the
	 * messager is asked to resume before being ready. In this case,
	 * <code>shouldResumeImmediately</code> will cause this actor to immediately
	 * place itself back in the scheduler.
	 */
	private boolean shouldResumeImmediately = false;

	/**
	 * Whether the messager is ready to resume. This is important if a message is
	 * sent synchronously and the receiver attempts to resume this messager before
	 * it has completed yielding, in which case the messager will enter an
	 * inconsistent state. Obviously, all messagers are ready to begin with.
	 */
	private boolean isReadyToResume = true;

	/**
	 * Temporary constructor to get File$native compiling. Remove once native
	 * method can retrieve their running actor.
	 * 
	 * @param state The internal state of the actor
	 */
	public Actor(Object state) {
		this(new Scheduler(0), state);
	}

	/**
	 * A convenience constructor for actors with no state.
	 * 
	 * @param scheduler The scheduler to use for concurrency
	 */
	public Actor(Scheduler scheduler) {
		this(scheduler, null);
	}

	/**
	 * @param scheduler The scheduler to use for concurrency
	 * @param state The internal state of the actor
	 */
	public Actor(Scheduler scheduler, Object state) {
		this.scheduler = scheduler;
		this.state = state;
	}

	/**
	 * @return The internal state of the actor
	 */
	public Object getState() {
		return state;
	}

	/**
	 * @param state The internal state of the actor
	 */
	public void setState(Object state) {
		this.state = state;
	}

	/**
	 * @return The scheduler used by this actor for concurrency
	 */
	public Scheduler getScheduler() {
		return scheduler;
	}

	/**
	 * Send a message asynchronously to this actor. If the mailbox is full, then
	 * this will in fact block.
	 * 
	 * @param sender The sender of this message
	 * @param method The method to call when running the message
	 * @param arguments The arguments to pass to the method
	 */
	public void asyncSend(Actor sender, Method method, Object[] arguments) {
		if (!sender.isYielded()) {
			// This is the first time this method is entered for this message.
			// Setup and add the message as normal.
			arguments[0] = this;
			Message message = new Message(method, arguments);
			if (!addMessage(message)) {
				// The message add failed. Save the message for the next time and yield
				// control of the thread.
				sender.yield(0);
				sender.set(0, message);

				// FIXME Should the sender just keep trying, or should this actor save
				// who has tried before and inform them when it's safe to try again?
				sender.schedule();
			}
		} else {
			// The sender has yielded and returned to this method. Attempt to add
			// the saved message again.
			Message message = (Message) sender.getObject(0);
			if (addMessage(message)) {
				sender.unyield();
			} else {
				// FIXME As above.
				sender.schedule();
			}
		}
	}

	/**
	 * Send a message synchronously to this actor. This will block the sender
	 * until the message is received, and a return value generated.
	 * 
	 * @param sender The sender of this message
	 * @param method The method to call when running the message
	 * @param arguments The arguments to pass to the method
	 * 
	 * @throws Throwable The method call may fail for any reason
	 */
	public Object syncSend(Actor sender, Method method, Object[] arguments)
	    throws Throwable {
		if (!sender.isYielded()) {
			// This is the first time this method is entered for this message.
			// Setup and add the message as normal.
			arguments[0] = this;
			SyncMessage message = new SyncMessage(sender, method, arguments);
			boolean added = addMessage(message);

			sender.yield(0);
			sender.set(0, added);
			sender.set(1, message);

			if (!added) {
				// FIXME As above.
				sender.schedule();
			}

			return null;
		} else if (sender.getBool(0)) {
			// The sender has yielded and returned to this method. The message add
			// was successful, so it's time to resume.
			SyncMessage message = (SyncMessage) currentMessage;

			// If the message failed, throw its failure exception.
			if (message.cause != null) {
				throw message.cause;
			}

			return message.result;
		} else {
			SyncMessage message = (SyncMessage) sender.getObject(1);
			boolean added = addMessage(message);

			sender.set(0, added);

			if (!added) {
				// FIXME As above.
				sender.schedule();
			}

			return null;
		}
	}

	/**
	 * Adds the given message to the end of the message linked-queue.
	 * 
	 * If the mailbox is full, this method returns false without modifying either
	 * end of the queue.
	 * 
	 * @param message The message to add
	 * 
	 * @return Whether the message was added successfully
	 */
	private synchronized boolean addMessage(Message message) {
		// TODO Potentially try to synchronise this method over the finalMessage if
		// it exists. Remains to be seen if this is trivial.
		if (mailboxSize == mailboxLimit) {
			return false;
		}

		if (currentMessage == null) {
			currentMessage = message;
		}

		if (finalMessage != null) {
			finalMessage.next = message;
		}

		mailboxSize += 1;
		finalMessage = message;

		return true;
	}

	@Override
	public void run() {
		while (currentMessage != null) {
			Message message = currentMessage;

			try {
				Object result = message.method.invoke(null, message.arguments);

				if (message instanceof SyncMessage && !this.isYielded()) {
					SyncMessage syncMessage = (SyncMessage) message;

					// Record the result and alert the sender that we've finished.
					syncMessage.result = result;
					syncMessage.sender.schedule();
				}
			} catch (IllegalArgumentException iax) {
				// Not possible - caught by the language compiler.
				System.err.println("Warning - illegal arguments in actor resumption.");
			} catch (IllegalAccessException e) {
				// Not possible - all message invocations are on public methods.
				System.err.println("Warning - illegal access in actor resumption.");
			} catch (InvocationTargetException itx) {
				Throwable cause = itx.getCause();

				// Respond to message failure.
				if (message instanceof SyncMessage) {
					SyncMessage syncMessage = (SyncMessage) message;

					// Record the result and alert the sender that we've finished.
					syncMessage.cause = cause;
					syncMessage.sender.schedule();
				} else {
					// This is here for debugging purposes.
					String reason = cause.getMessage();

					System.err.print(this + " failed in a message to "
					    + message.method.getName() + " because ");

					if (cause instanceof wyjc.runtime.Exception) {
						System.err.println(cause);
					} else if (reason == null) {
						System.err.println("of a " + cause);
					} else {
						System.err.println(reason);
					}
				}
			}

			if (!this.isYielded()) {
				synchronized (this) {
					currentMessage = message.next;
				}
			} else {
				// This needs to be synchronized in case another actor is trying to
				// schedule this one during the end. In this case, it may see
				// isReadyToResume as false, but then fail to set
				// shouldResumeImmediately to true before the if statement below,
				// which would result in the actor never resuming.
				synchronized (this) {
					isReadyToResume = true;
				}

				if (shouldResumeImmediately) {
					schedule();
				}
			}
		}
	}

	/**
	 * Schedules the actor to resume in the future.
	 * 
	 * If the actor is currently in the processing of yielding from a thread,
	 * then this method will only alert it to schedule itself once it has
	 * completed. This will prevent the actor from yielding and unyielding at the
	 * same time.
	 */
	private void schedule() {
		// This is where the resume race condition needs to be handled. The
		// synchronization prevents this code from running alongside the block in
		// the run method: see the comment there.
		synchronized (this) {

			// If a schedule is requested while the actor is unwinding its call stack
			// then this will cause the schedule to happen once the unwind is done.
			if (!isReadyToResume) {
				shouldResumeImmediately = true;
				return;
			}

		}

		// Schedule is successful, so restore these to default values.
		isReadyToResume = shouldResumeImmediately = false;

		scheduler.schedule(this);
	}

	@Override
	public String toString() {
		return state + "@" + System.identityHashCode(this);
	}

	private static class Message {

		public final Method method;
		public final Object[] arguments;

		public Message next = null;

		public Message(Method method, Object[] arguments) {
			this.method = method;
			this.arguments = arguments;
		}

	}

	private static final class SyncMessage extends Message {

		public final Actor sender;
		public Object result = null;
		public Throwable cause = null;

		public SyncMessage(Actor sender, Method method, Object[] arguments) {
			super(method, arguments);
			this.sender = sender;
		}

	}

}
