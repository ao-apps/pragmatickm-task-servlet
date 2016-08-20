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
package com.pragmatickm.task.servlet.impl;

import com.aoindustries.encoding.MediaWriter;
import com.aoindustries.encoding.TextInXhtmlAttributeEncoder;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.encodeTextInXhtmlAttribute;
import static com.aoindustries.encoding.TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder;
import static com.aoindustries.encoding.TextInXhtmlEncoder.encodeTextInXhtml;
import com.aoindustries.io.buffer.BufferResult;
import com.aoindustries.util.CalendarUtils;
import com.aoindustries.util.schedule.Recurring;
import com.pragmatickm.task.model.Priority;
import com.pragmatickm.task.model.Task;
import com.pragmatickm.task.model.TaskException;
import com.pragmatickm.task.model.TaskLookup;
import com.pragmatickm.task.model.TaskPriority;
import com.pragmatickm.task.servlet.TaskUtil;
import com.semanticcms.core.model.ElementContext;
import com.semanticcms.core.model.NodeBodyWriter;
import com.semanticcms.core.model.Page;
import com.semanticcms.core.model.PageRef;
import com.semanticcms.core.servlet.CaptureLevel;
import com.semanticcms.core.servlet.CurrentPage;
import com.semanticcms.core.servlet.PageIndex;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final public class TaskImpl {

	private static final String REMOVE_JSP_EXTENSION = ".jsp";
	private static final String REMOVE_JSPX_EXTENSION = ".jspx";
	private static final String TASKLOG_MID = "-tasklog-";
	private static final String TASKLOG_EXTENSION = ".xml";

	private static void writeRow(String header, String value, Writer out) throws IOException {
		if(value != null) {
			out.write("<tr><th>");
			encodeTextInXhtml(header, out);
			out.write("</th><td colspan=\"3\">");
			encodeTextInXhtml(value, out);
			out.write("</td></tr>\n");
		}
	}

	private static void writeRow(String header, List<?> values, Writer out) throws IOException {
		if(values != null) {
			int size = values.size();
			if(size > 0) {
				out.write("<tr><th>");
				encodeTextInXhtml(header, out);
				out.write("</th><td colspan=\"3\">");
				for(int i=0; i<size; i++) {
					encodeTextInXhtml(values.get(i).toString(), out);
					if(i != (size - 1)) out.write("<br />");
				}
				out.write("</td></tr>\n");
			}
		}
	}

	private static void writeRow(String header, Calendar date, Writer out) throws IOException {
		if(date != null) writeRow(header, CalendarUtils.formatDate(date), out);
	}

	private static void writeRow(String header, Recurring recurring, boolean relative, Writer out) throws IOException {
		if(recurring != null) {
			writeRow(
				header,
				relative
					? (recurring.getRecurringDisplay() + " (Relative)")
					: recurring.getRecurringDisplay(),
				out
			);
		}
	}

	public static void writeBeforeBody(
		ServletContext servletContext,
		HttpServletRequest request,
		HttpServletResponse response,
		CaptureLevel captureLevel,
		Writer out,
		Task task,
		String style
	) throws TaskException, IOException, ServletException {
		final Page currentPage = CurrentPage.getCurrentPage(request);

		// Verify consistency between attributes
		Recurring recurring = task.getRecurring();
		boolean relative = task.getRelative();
		if(recurring!=null) {
			Calendar on = task.getOn();
			if(on == null) {
				if(!relative) throw new ServletException("Task \"on\" attribute required for non-relative recurring tasks.");
			} else {
				String checkResult = recurring.checkScheduleFrom(on, "on");
				if(checkResult != null) throw new ServletException("Task: " + checkResult);
			}
		} else {
			if(relative) {
				throw new ServletException("Task \"relative\" attribute only allowed for recurring tasks.");
			}
		}

		if(captureLevel == CaptureLevel.BODY) {
			// Write the task itself to this page
			final PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
			out.write("<table id=\"");
			PageIndex.appendIdInPage(
				pageIndex,
				currentPage,
				task.getId(),
				new MediaWriter(TextInXhtmlAttributeEncoder.textInXhtmlAttributeEncoder, out)
			);
			out.write("\" class=\"thinTable taskTable\"");
			if(style!=null) {
				out.write(" style=\"");
				encodeTextInXhtmlAttribute(style, out);
				out.write('"');
			}
			out.write(">\n"
					+ "<thead><tr><th class=\"taskTableHeader\" colspan=\"4\"><div>");
			encodeTextInXhtml(task.getLabel(), out);
			out.write("</div></th></tr></thead>\n"
					+ "<tbody>\n");
			List<TaskLookup> doBeforeLookups = task.getDoBefores();
			final long now = System.currentTimeMillis();
			writeTaskLookups(request, response, out, currentPage, now, doBeforeLookups, "Do Before:");
			out.write("<tr><th>Status:</th><td class=\"");
			Task.StatusResult status = task.getStatus();
			encodeTextInXhtmlAttribute(status.getCssClass().name(), out);
			out.write("\" colspan=\"3\">");
			encodeTextInXhtml(status.getDescription(), out);
			out.write("</td></tr>\n");
			String comments = status.getComments();
			if(comments != null && !comments.isEmpty()) {
				out.write("<tr><th>Status Comment:</th><td colspan=\"3\">");
				encodeTextInXhtml(comments, out);
				out.write("</td></tr>\n");
			}
			List<TaskPriority> taskPriorities = task.getPriorities();
			for(int i=0, size=taskPriorities.size(); i<size; i++) {
				TaskPriority taskPriority = taskPriorities.get(i);
				out.write("<tr>");
				if(i==0) {
					out.write("<th");
					if(size != 1) {
						out.write(" rowspan=\"");
						out.write(Integer.toString(size));
						out.write('"');
					}
					out.write(">Priority:</th>");
				}
				out.write("<td class=\"");
				Priority priority = taskPriority.getPriority();
				encodeTextInXhtmlAttribute(priority.getCssClass(), out);
				out.write("\" colspan=\"3\">");
				encodeTextInXhtml(taskPriority.toString(), out);
				out.write("</td></tr>\n");
			}
			writeRow(recurring==null ? "On:" : "Starting:", task.getOn(), out);
			writeRow("Recurring:", recurring, relative, out);
			writeRow("Assigned To:", task.getAssignedTo(), out);
			writeRow("Pay:", task.getPay(), out);
			writeRow("Cost:", task.getCost(), out);
			List<Task> doAfters = TaskUtil.getDoAfters(servletContext, request, response, task);
			writeTasks(request, response, out, currentPage, now, doAfters, "Do After:");
		}
	}

	public static void writeAfterBody(Task task, Writer out, ElementContext context) throws IOException {
		BufferResult body = task.getBody();
		if(body.getLength() > 0) {
			out.write("<tr><td colspan=\"4\">\n");
			body.writeTo(new NodeBodyWriter(task, out, context));
			out.write("\n</td></tr>\n");
		}
		out.write("</tbody>\n"
				+ "</table>");
	}

	/**
	 * Gets the file that stores the XML data for a task log.
	 */
	public static PageRef getTaskLogXmlFile(PageRef pageRef, String taskId) {
		String xmlFilePath = pageRef.getPath();
		if(xmlFilePath.endsWith(REMOVE_JSP_EXTENSION)) {
			xmlFilePath = xmlFilePath.substring(0, xmlFilePath.length() - REMOVE_JSP_EXTENSION.length());
		} else if(xmlFilePath.endsWith(REMOVE_JSPX_EXTENSION)) {
			xmlFilePath = xmlFilePath.substring(0, xmlFilePath.length() - REMOVE_JSPX_EXTENSION.length());
		}
		xmlFilePath = xmlFilePath + TASKLOG_MID + taskId + TASKLOG_EXTENSION;
		return new PageRef(pageRef.getBook(), xmlFilePath);
	}

	private static void writeTasks(
		HttpServletRequest request,
		HttpServletResponse response,
		Writer out,
		Page currentPage,
		long now,
		List<? extends Task> tasks,
		String label
	) throws IOException, TaskException {
		int size = tasks.size();
		if(size>0) {
			for(int i=0; i<size; i++) {
				final Task task = tasks.get(i);
				final Page taskPage = task.getPage();
				final Task.StatusResult status = task.getStatus();
				final Priority priority;
				if(status.getDate() != null) {
					priority = task.getPriority(status.getDate(), now);
				} else {
					priority = task.getZeroDayPriority();
				}
				out.write("<tr>");
				if(i==0) {
					out.write("<th rowspan=\"");
					encodeTextInXhtmlAttribute(Integer.toString(size), out);
					out.write("\">");
					encodeTextInXhtml(label, out);
					out.write("</th>");
				}
				out.write("<td class=\"");
				encodeTextInXhtmlAttribute(status.getCssClass().name(), out);
				out.write("\">");
				encodeTextInXhtml(status.getDescription(), out);
				out.write("</td><td class=\"");
				encodeTextInXhtmlAttribute(priority.getCssClass(), out);
				out.write("\">");
				encodeTextInXhtml(priority.toString(), out);
				out.write("</td><td><a class=\"taskLink\" href=\"");
				PageIndex pageIndex = PageIndex.getCurrentPageIndex(request);
				Integer index = pageIndex==null ? null : pageIndex.getPageIndex(taskPage.getPageRef());
				if(index != null) {
					// view=all mode
					out.write('#');
					PageIndex.appendIdInPage(
						index,
						task.getId(),
						new MediaWriter(textInXhtmlAttributeEncoder, out)
					);
				} else if(taskPage.equals(currentPage)) {
					// Task on this page, generate anchor-only link
					encodeTextInXhtmlAttribute('#', out);
					encodeTextInXhtmlAttribute(task.getId(), out);
				} else {
					// Task on other page, generate full link
					encodeTextInXhtmlAttribute(
						response.encodeURL(
							com.aoindustries.net.UrlUtils.encodeUrlPath(
								request.getContextPath()
									+ taskPage.getPageRef().getServletPath()
									+ '#' + task.getId(),
								response.getCharacterEncoding()
							)
						),
						out
					);
				}
				out.write("\">");
				encodeTextInXhtml(task.getLabel(), out);
				if(index != null) {
					out.write("<sup>[");
					encodeTextInXhtml(Integer.toString(index+1), out);
					out.write("]</sup>");
				}
				out.write("</a></td></tr>\n");
			}
		}
	}

	private static void writeTaskLookups(
		HttpServletRequest request,
		HttpServletResponse response,
		Writer out,
		Page currentPage,
		long now,
		List<TaskLookup> taskLookups,
		String label
	) throws TaskException, IOException {
		int size = taskLookups.size();
		if(size>0) {
			List<Task> tasks = new ArrayList<Task>(size);
			for(TaskLookup taskLookup : taskLookups) tasks.add(taskLookup.getTask());
			writeTasks(
				request,
				response,
				out,
				currentPage,
				now,
				tasks,
				label
			);
		}
	}

	/**
	 * Make no instances.
	 */
	private TaskImpl() {
	}
}
