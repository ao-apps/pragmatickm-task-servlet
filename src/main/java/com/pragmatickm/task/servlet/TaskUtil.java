/*
 * pragmatickm-task-servlet - Tasks nested within SemanticCMS pages and elements in a Servlet environment.
 * Copyright (C) 2013, 2014, 2015, 2016  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of pragmatickm-task-servlet.
 *
 * pragmatickm-task-servlet is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * pragmatickm-task-servlet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with pragmatickm-task-servlet.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pragmatickm.task.servlet;

import com.aoindustries.util.ComparatorUtils;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.Tuple2;
import com.aoindustries.util.UnmodifiableCalendar;
import com.aoindustries.util.WrappedException;
import com.pragmatickm.task.model.Priority;
import com.pragmatickm.task.model.Task;
import com.pragmatickm.task.model.TaskAssignment;
import com.pragmatickm.task.model.TaskException;
import com.pragmatickm.task.model.TaskLog;
import com.pragmatickm.task.model.TaskLookup;
import com.pragmatickm.task.model.User;
import com.pragmatickm.task.servlet.impl.TaskImpl;
import com.semanticcms.core.model.Element;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.servlet.Cache;
import com.semanticcms.core.servlet.CacheFilter;
import com.semanticcms.core.servlet.CaptureLevel;
import com.semanticcms.core.servlet.CapturePage;
import com.semanticcms.core.servlet.PageRefResolver;
import com.semanticcms.core.servlet.SemanticCMS;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final public class TaskUtil {

	public static TaskLog getTaskLogInBook(
		ServletContext servletContext,
		HttpServletRequest request,
		String book,
		String page,
		String taskId
	) throws ServletException, IOException {
		PageRef pageRef = PageRefResolver.getPageRef(
			servletContext,
			request,
			book,
			page
		);
		if(pageRef.getBook()==null) throw new IllegalArgumentException("Book not found: " + pageRef.getBookName());
		return TaskLog.getTaskLog(
			TaskImpl.getTaskLogXmlFile(pageRef, taskId)
		);
	}

	public static TaskLog getTaskLog(
		ServletContext servletContext,
		HttpServletRequest request,
		String page,
		String taskId
	) throws ServletException, IOException {
		return getTaskLogInBook(
			servletContext,
			request,
			null,
			page,
			taskId
		);
	}

	public static TaskLog.Entry getMostRecentEntry(TaskLog taskLog, String statuses) throws IOException {
		List<String> split = StringUtility.splitStringCommaSpace(statuses);
		List<TaskLog.Entry> entries = taskLog.getEntries();
		for(int i=entries.size()-1; i>=0; i--) {
			TaskLog.Entry entry = entries.get(i);
			String label = entry.getStatus().getLabel();
			for(String status : split) {
				if(label.equalsIgnoreCase(status)) {
					return entry;
				}
			}
		}
		return null;
	}

	/**
	 * Finds all tasks that must be done after this task.
	 * This requires a capture of the entire page tree
	 * meta data to find any task that has a doBefore pointing to this task.
	 */
	public static List<Task> getDoAfters(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Task task
	) throws ServletException, IOException {
		final String taskId = task.getId();
		final Page taskPage = task.getPage();
		final List<Task> doAfters = new ArrayList<Task>();
		CapturePage.traversePagesDepthFirst(
			servletContext,
			request,
			response,
			SemanticCMS.getInstance(servletContext).getRootBook().getContentRoot(),
			CaptureLevel.META,
			new CapturePage.PageHandler<Void>() {
				@Override
				public Void handlePage(Page page) throws ServletException, IOException {
					try {
						for(Element element : page.getElements()) {
							if(element instanceof Task) {
								Task pageTask = (Task)element;
								for(TaskLookup doBeforeLookup : pageTask.getDoBefores()) {
									Task doBefore = doBeforeLookup.getTask();
									if(
										doBefore.getPage().equals(taskPage)
										&& doBefore.getId().equals(taskId)
									) {
										doAfters.add(pageTask);
									}
								}
							}
						}
					} catch(TaskException e) {
						throw new ServletException(e);
					}
					return null;
				}
			},
			new CapturePage.TraversalEdges() {
				@Override
				public Collection<PageRef> getEdges(Page page) {
					return page.getChildPages();
				}
			},
			new CapturePage.EdgeFilter() {
				@Override
				public boolean applyEdge(PageRef childPage) {
					return childPage.getBook() != null;
				}
			},
			null
		);
		return Collections.unmodifiableList(doAfters);
	}

	/**
	 * Finds all tasks that must be done after each of the provided tasks.
	 * This requires a capture of the entire page tree
	 * meta data to find any task that has a doBefore pointing to each task.
	 *
	 * @return  The map of doAfters, in the same iteration order as the provided
	 *          tasks.  If no doAfters for a given task, will contain an empty list.
	 */
	public static Map<Task,List<Task>> getMultipleDoAfters(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Collection<? extends Task> tasks
	) throws ServletException, IOException {
		int size = tasks.size();
		if(size == 0) {
			return Collections.emptyMap();
		} else if(size == 1) {
			Task task = tasks.iterator().next();
			return Collections.singletonMap(
				task,
				getDoAfters(servletContext, request, response, task)
			);
		} else {
			final Map<Task,List<Task>> results = new LinkedHashMap<Task,List<Task>>(size *4/3+1);
			// Fill with empty lists, this sets the iteration order, too
			{
				List<Task> emptyList = Collections.emptyList();
				for(Task task : tasks) results.put(task, emptyList);
			}
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				SemanticCMS.getInstance(servletContext).getRootBook().getContentRoot(),
				CaptureLevel.META,
				new CapturePage.PageHandler<Void>() {
					@Override
					public Void handlePage(Page page) throws ServletException, IOException {
						try {
							for(Element element : page.getElements()) {
								if(element instanceof Task) {
									Task pageTask = (Task)element;
									for(TaskLookup doBeforeLookup : pageTask.getDoBefores()) {
										Task doBefore = doBeforeLookup.getTask();
										List<Task> doAfters = results.get(doBefore);
										if(doAfters != null) {
											int doAftersSize = doAfters.size();
											if(doAftersSize == 0) {
												results.put(doBefore, Collections.singletonList(pageTask));
											} else {
												if(doAftersSize == 1) {
													Task first = doAfters.get(0);
													doAfters = new ArrayList<Task>();
													doAfters.add(first);
													results.put(doBefore, doAfters);
												}
												doAfters.add(pageTask);
											}
										}
									}
								}
							}
						} catch(TaskException e) {
							throw new ServletException(e);
						}
						return null;
					}
				},
				new CapturePage.TraversalEdges() {
					@Override
					public Collection<PageRef> getEdges(Page page) {
						return page.getChildPages();
					}
				},
				new CapturePage.EdgeFilter() {
					@Override
					public boolean applyEdge(PageRef childPage) {
						return childPage.getBook() != null;
					}
				},
				null
			);
			// Wrap any with size of 2 or more with unmodifiable, 0 and 1 already are unmodifiable
			for(Map.Entry<Task,List<Task>> entry : results.entrySet()) {
				List<Task> doAfters = entry.getValue();
				if(doAfters.size() > 1) entry.setValue(Collections.unmodifiableList(doAfters));
			}
			// Make entire map unmodifiable
			return Collections.unmodifiableMap(results);
		}
	}

	public static User getUser(
		HttpServletRequest request,
		HttpServletResponse response
	) {
		String userParam = request.getParameter("user");
		if(userParam != null) {
			// Find and set cookie
			User user = userParam.isEmpty() ? null : User.valueOf(userParam);
			Cookies.setUser(request, response, user);
			return user;
		} else {
			// Get from cookie
			return Cookies.getUser(request);
		}
	}

	public static Set<User> getAllUsers() {
		return EnumSet.allOf(User.class);
	}

	private static class TaskKey {
		private final PageRef pageRef;
		private final String taskId;

		private TaskKey(PageRef pageRef, String taskId) {
			this.pageRef = pageRef;
			this.taskId = taskId;
		}

		@Override
		public boolean equals(Object o) {
			if(!(o instanceof TaskKey)) return false;
			TaskKey other = (TaskKey)o;
			return
				pageRef.equals(other.pageRef)
				&& taskId.equals(other.taskId)
			;
		}

		@Override
		public int hashCode() {
			return pageRef.hashCode() * 31 + taskId.hashCode();
		}
	}

	private static Priority getEffectivePriority(
		long now,
		Task task,
		Task.StatusResult status,
		Map<Task,List<Task>> doAftersByTask,
		Map<Task,Priority> effectivePriorities
	) throws TaskException, IOException {
		Priority cached = effectivePriorities.get(task);
		if(cached != null) return cached;
		// Find the maximum priority of this task and all that will be done after it
		Priority effective = TaskImpl.getPriorityForStatus(now, task, status);
		if(effective != Priority.MAX_PRIORITY) {
			List<Task> doAfters = doAftersByTask.get(task);
			if(doAfters != null) {
				for(Task doAfter : doAfters) {
					Task.StatusResult doAfterStatus = doAfter.getStatus();
					if(
						!doAfterStatus.isCompletedSchedule()
						&& !doAfterStatus.isReadySchedule()
						&& !doAfterStatus.isFutureSchedule()
					) {
						Priority inherited = getEffectivePriority(now, doAfter, doAfterStatus, doAftersByTask, effectivePriorities);
						if(inherited.compareTo(effective) > 0) {
							effective = inherited;
							if(effective == Priority.MAX_PRIORITY) break;
						}
					}
				}
			}
		}
		// Cache result
		effectivePriorities.put(task, effective);
		return effective;
	}

	public static List<Task> prioritizeTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		List<? extends Task> tasks,
		final boolean dateFirst
	) throws ServletException, IOException {
		final long now = System.currentTimeMillis();
		// Priority inheritance
		List<Task> allTasks = getAllTasks(
			servletContext,
			request,
			response,
			CapturePage.capturePage(
				servletContext,
				request,
				response,
				SemanticCMS.getInstance(servletContext).getRootBook().getContentRoot(),
				CaptureLevel.META
			),
			null
		);
		// Index tasks by page,id
		Map<TaskKey,Task> tasksByKey = new HashMap<TaskKey,Task>(allTasks.size()*4/3+1);
		for(Task task : allTasks) {
			if(
				tasksByKey.put(
					new TaskKey(
						task.getPage().getPageRef(),
						task.getId()
					),
					task
				) != null
			) throw new AssertionError("Duplicate task (page, id)");
		}
		// Invert dependency DAG for fast lookups for priority inheritance
		final Map<Task,List<Task>> doAftersByTask = new LinkedHashMap<Task,List<Task>>(allTasks.size()*4/3+1);
		for(Task task : allTasks) {
			for(TaskLookup doBeforeLookup : task.getDoBefores()) {
				Task doBefore = tasksByKey.get(
					new TaskKey(
						doBeforeLookup.getPageRef(),
						doBeforeLookup.getTaskId()
					)
				);
				if(doBefore==null) throw new AssertionError("Task not found: page=" + doBeforeLookup.getPageRef()+", id=" + doBeforeLookup.getTaskId());
				List<Task> doAfters = doAftersByTask.get(doBefore);
				if(doAfters == null) {
					doAfters = new ArrayList<Task>();
					doAftersByTask.put(doBefore, doAfters);
				}
				doAfters.add(task);
			}
		}
		// Caches the effective priorities for tasks being prioritized or any other resolved in processing
		final Map<Task,Priority> effectivePriorities = new HashMap<Task,Priority>();
		// Build new list and sort
		List<Task> sortedTasks = new ArrayList<Task>(tasks);
		Collections.sort(
			sortedTasks,
			new Comparator<Task>() {
				private int dateDiff(Task t1, Task t2) throws TaskException, IOException {
					// Sort by scheduled or unscheduled
					Task.StatusResult status1 = t1.getStatus();
					Task.StatusResult status2 = t2.getStatus();
					Calendar date1 = status1.getDate();
					Calendar date2 = status2.getDate();
					int diff = ComparatorUtils.compare(date2!=null, date1!=null);
					if(diff!=0) return diff;
					// Then sort by date (if have date in both statuses)
					if(date1!=null && date2!=null) {
						diff = date1.compareTo(date2);
						if(diff!=0) return diff;
					}
					// Dates equal
					return 0;
				}

				@Override
				public int compare(Task t1, Task t2) {
					try {
						// Sort by date (when date first)
						if(dateFirst) {
							int diff = dateDiff(t1, t2);
							if(diff!=0) return diff;
						}
						// Sort by priority (including priority inheritance)
						Priority priority1 = getEffectivePriority(now, t1, t1.getStatus(), doAftersByTask, effectivePriorities);
						Priority priority2 = getEffectivePriority(now, t2, t2.getStatus(), doAftersByTask, effectivePriorities);
						int diff = priority2.compareTo(priority1);
						if(diff!=0) return diff;
						// Sort by date (when priority first)
						if(!dateFirst) {
							diff = dateDiff(t1, t2);
							if(diff!=0) return diff;
						}
						// Equal
						return 0;
					} catch(TaskException e) {
						throw new WrappedException(e);
					} catch(IOException e) {
						throw new WrappedException(e);
					}
				}
			}
		);
		return Collections.unmodifiableList(sortedTasks);
	}

	private static <V> Map<PageUserKey,V> getPageUserCache(
		final Cache cache,
		String key
	) {
		@SuppressWarnings("unchecked")
		Map<PageUserKey,V> pageUserCache = cache.getAttribute(
			key,
			Map.class,
			new Cache.Callable<Map<PageUserKey,V>, RuntimeException>() {
				@Override
				public Map<PageUserKey, V> call() throws RuntimeException {
					return cache.newMap();
				}
			}
		);
		return pageUserCache;
	}

	static class PageUserKey extends Tuple2<Page,User> {
		PageUserKey(Page page, User user) {
			super(page, user);
		}
	}

	private static final String ALL_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getAllTasks";

	public static List<Task> getAllTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		Map<PageUserKey,List<Task>> cache = getPageUserCache(CacheFilter.getCache(request), ALL_TASKS_CACHE_KEY);
		List<Task> results = cache.get(cacheKey);
		if(results == null) {
			final List<Task> allTasks = new ArrayList<Task>();
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				new CapturePage.PageHandler<Void>() {
					@Override
					public Void handlePage(Page page) throws ServletException, IOException {
						for(Element element : page.getElements()) {
							if(element instanceof Task) {
								Task task = (Task)element;
								if(
									user == null
									|| task.getAssignedTo(user) != null
								) allTasks.add(task);
							}
						}
						return null;
					}
				},
				new CapturePage.TraversalEdges() {
					@Override
					public Collection<PageRef> getEdges(Page page) {
						return page.getChildPages();
					}
				},
				new CapturePage.EdgeFilter() {
					@Override
					public boolean applyEdge(PageRef childPage) {
						// Child not in missing book
						return childPage.getBook() != null;
					}
				},
				null
			);
			results = Collections.unmodifiableList(allTasks);
			cache.put(cacheKey, results);
		}
		return results;
	}

	private static final String HAS_ASSIGNED_TASK_CACHE_KEY = TaskUtil.class.getName() + ".hasAssignedTask";

	public static boolean hasAssignedTask(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page page,
		final User user
	) throws ServletException, IOException {
		PageUserKey cacheKey = new PageUserKey(page, user);
		Map<PageUserKey,Boolean> cache = getPageUserCache(CacheFilter.getCache(request), HAS_ASSIGNED_TASK_CACHE_KEY);
		Boolean result = cache.get(cacheKey);
		if(result == null) {
			final long now = System.currentTimeMillis();
			result = CapturePage.traversePagesAnyOrder(
				servletContext,
				request,
				response,
				page,
				CaptureLevel.META,
				new CapturePage.PageHandler<Boolean>() {
					@Override
					public Boolean handlePage(Page page) throws ServletException, IOException {
						try {
							for(Element element : page.getElements()) {
								if(element instanceof Task) {
									Task task = (Task)element;
									TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
									if(
										user == null
										|| assignedTo != null
									) {
										Task.StatusResult status = task.getStatus();
										Priority priority = null;
										// getReadyTasks logic
										if(
											!status.isCompletedSchedule()
											&& status.isReadySchedule()
										) {
											priority = TaskImpl.getPriorityForStatus(now, task, status);
											if(priority != Priority.FUTURE) {
												if(
													status.getDate() != null
													&& assignedTo != null
													&& assignedTo.getAfter().getCount() > 0
												) {
													// assignedTo "after"
													Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
													assignedTo.getAfter().offset(effectiveDate);
													if(now >= effectiveDate.getTimeInMillis()) {
														return true;
													}
												} else {
													// No time offset
													return true;
												}
											}
										}
										// getBlockedTasks logic
										if(
											!status.isCompletedSchedule()
											&& !status.isReadySchedule()
											&& !status.isFutureSchedule()
										) {
											if(priority == null) {
												priority = TaskImpl.getPriorityForStatus(now, task, status);
											}
											if(priority != Priority.FUTURE) {
												if(
													status.getDate() != null
													&& assignedTo != null
													&& assignedTo.getAfter().getCount() > 0
												) {
													// assignedTo "after"
													Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
													assignedTo.getAfter().offset(effectiveDate);
													if(now >= effectiveDate.getTimeInMillis()) {
														return true;
													}
												} else {
													// No time offset
													return true;
												}
											}
										}
										// getFutureTasks logic
										if(
											// When assignedTo "after" is non-zero, hide from this user
											assignedTo == null
											|| assignedTo.getAfter().getCount() == 0
										) {
											boolean future = status.isFutureSchedule();
											if(!future) {
												if(priority == null) {
													priority = TaskImpl.getPriorityForStatus(now, task, status);
												}
												future = priority == Priority.FUTURE;
											}
											if(future) {
												return true;
											}
										}
									}
								}
							}
							return null;
						} catch(TaskException e) {
							throw new ServletException(e);
						}
					}
				},
				new CapturePage.TraversalEdges() {
					@Override
					public Collection<PageRef> getEdges(Page page) {
						return page.getChildPages();
					}
				},
				new CapturePage.EdgeFilter() {
					@Override
					public boolean applyEdge(PageRef childPage) {
						// Child not in missing book
						return childPage.getBook() != null;
					}
				}
			) != null;
			cache.put(cacheKey, result);
		}
		return result;
	}

	private static final String GET_READY_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getReadyTasks";

	public static List<Task> getReadyTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		Map<PageUserKey,List<Task>> cache = getPageUserCache(CacheFilter.getCache(request), GET_READY_TASKS_CACHE_KEY);
		List<Task> results = cache.get(cacheKey);
		if(results == null) {
			final long now = System.currentTimeMillis();
			final List<Task> readyTasks = new ArrayList<Task>();
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				new CapturePage.PageHandler<Void>() {
					@Override
					public Void handlePage(Page page) throws ServletException, IOException {
						try {
							for(Element element : page.getElements()) {
								if(element instanceof Task) {
									Task task = (Task)element;
									TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
									if(
										user == null
										|| assignedTo != null
									) {
										Task.StatusResult status = task.getStatus();
										if(
											!status.isCompletedSchedule()
											&& status.isReadySchedule()
										) {
											Priority priority = TaskImpl.getPriorityForStatus(now, task, status);
											if(priority != Priority.FUTURE) {
												if(
													status.getDate() != null
													&& assignedTo != null
													&& assignedTo.getAfter().getCount() > 0
												) {
													// assignedTo "after"
													Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
													assignedTo.getAfter().offset(effectiveDate);
													if(now >= effectiveDate.getTimeInMillis()) {
														readyTasks.add(task);
													}
												} else {
													// No time offset
													readyTasks.add(task);
												}
											}
										}
									}
								}
							}
							return null;
						} catch(TaskException e) {
							throw new ServletException(e);
						}
					}
				},
				new CapturePage.TraversalEdges() {
					@Override
					public Collection<PageRef> getEdges(Page page) {
						return page.getChildPages();
					}
				},
				new CapturePage.EdgeFilter() {
					@Override
					public boolean applyEdge(PageRef childPage) {
						// Child not in missing book
						return childPage.getBook() != null;
					}
				},
				null
			);
			results = Collections.unmodifiableList(readyTasks);
			cache.put(cacheKey, results);
		}
		return results;
	}

	private static final String GET_BLOCKED_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getBlockedTasks";

	public static List<Task> getBlockedTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		Map<PageUserKey,List<Task>> cache = getPageUserCache(CacheFilter.getCache(request), GET_BLOCKED_TASKS_CACHE_KEY);
		List<Task> results = cache.get(cacheKey);
		if(results == null) {
			final long now = System.currentTimeMillis();
			final List<Task> blockedTasks = new ArrayList<Task>();
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				new CapturePage.PageHandler<Void>() {
					@Override
					public Void handlePage(Page page) throws ServletException, IOException {
						try {
							for(Element element : page.getElements()) {
								if(element instanceof Task) {
									Task task = (Task)element;
									TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
									if(
										user == null
										|| assignedTo != null
									) {
										Task.StatusResult status = task.getStatus();
										if(
											!status.isCompletedSchedule()
											&& !status.isReadySchedule()
											&& !status.isFutureSchedule()
										) {
											Priority priority = TaskImpl.getPriorityForStatus(now, task, status);
											if(priority != Priority.FUTURE) {
												if(
													status.getDate() != null
													&& assignedTo != null
													&& assignedTo.getAfter().getCount() > 0
												) {
													// assignedTo "after"
													Calendar effectiveDate = UnmodifiableCalendar.unwrapClone(status.getDate());
													assignedTo.getAfter().offset(effectiveDate);
													if(now >= effectiveDate.getTimeInMillis()) {
														blockedTasks.add(task);
													}
												} else {
													// No time offset
													blockedTasks.add(task);
												}
											}
										}
									}
								}
							}
							return null;
						} catch(TaskException e) {
							throw new ServletException(e);
						}
					}
				},
				new CapturePage.TraversalEdges() {
					@Override
					public Collection<PageRef> getEdges(Page page) {
						return page.getChildPages();
					}
				},
				new CapturePage.EdgeFilter() {
					@Override
					public boolean applyEdge(PageRef childPage) {
						// Child not in missing book
						return childPage.getBook() != null;
					}
				},
				null
			);
			results = Collections.unmodifiableList(blockedTasks);
			cache.put(cacheKey, results);
		}
		return results;
	}

	private static final String FUTURE_TASKS_CACHE_KEY = TaskUtil.class.getName() + ".getFutureTasks";

	public static List<Task> getFutureTasks(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		Page rootPage,
		final User user
	) throws IOException, ServletException {
		PageUserKey cacheKey = new PageUserKey(rootPage, user);
		Map<PageUserKey,List<Task>> cache = getPageUserCache(CacheFilter.getCache(request), FUTURE_TASKS_CACHE_KEY);
		List<Task> results = cache.get(cacheKey);
		if(results == null) {
			final long now = System.currentTimeMillis();
			final List<Task> futureTasks = new ArrayList<Task>();
			CapturePage.traversePagesDepthFirst(
				servletContext,
				request,
				response,
				rootPage,
				CaptureLevel.META,
				new CapturePage.PageHandler<Void>() {
					@Override
					public Void handlePage(Page page) throws ServletException, IOException {
						try {
							for(Element element : page.getElements()) {
								if(element instanceof Task) {
									Task task = (Task)element;
									TaskAssignment assignedTo = user == null ? null : task.getAssignedTo(user);
									if(
										(
											user == null
											|| assignedTo != null
										) && (
											// When assignedTo "after" is non-zero, hide from this user
											assignedTo == null
											|| assignedTo.getAfter().getCount() == 0
										)
									) {
										Task.StatusResult status = task.getStatus();
										boolean future = status.isFutureSchedule();
										if(!future) {
											Priority priority = TaskImpl.getPriorityForStatus(now, task, status);
											future = priority == Priority.FUTURE;
										}
										if(future) {
											futureTasks.add(task);
										}
									}
								}
							}
							return null;
						} catch(TaskException e) {
							throw new ServletException(e);
						}
					}
				},
				new CapturePage.TraversalEdges() {
					@Override
					public Collection<PageRef> getEdges(Page page) {
						return page.getChildPages();
					}
				},
				new CapturePage.EdgeFilter() {
					@Override
					public boolean applyEdge(PageRef childPage) {
						// Child not in missing book
						return childPage.getBook() != null;
					}
				},
				null
			);
			results = Collections.unmodifiableList(futureTasks);
			cache.put(cacheKey, results);
		}
		return results;
	}

	/**
	 * Make no instances.
	 */
	private TaskUtil() {
	}
}