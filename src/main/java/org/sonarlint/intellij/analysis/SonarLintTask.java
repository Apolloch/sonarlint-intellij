/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.analysis;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.sonarlint.intellij.editor.AccumulatorIssueListener;
import org.sonarlint.intellij.issue.IssueProcessor;
import org.sonarlint.intellij.messages.TaskListener;
import org.sonarlint.intellij.ui.SonarLintConsole;
import org.sonarlint.intellij.util.SonarLintUtils;
import org.sonarsource.sonarlint.core.client.api.common.analysis.AnalysisResults;
import org.sonarsource.sonarlint.core.client.api.common.analysis.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.common.analysis.Issue;
import org.sonarsource.sonarlint.core.client.api.exceptions.CanceledException;

public class SonarLintTask extends Task.Backgroundable {
  private static final Logger LOGGER = Logger.getInstance(SonarLintTask.class);
  private final IssueProcessor processor;
  protected final SonarLintJob job;
  protected final boolean modal;
  private final boolean startInBackground;
  private final SonarLintConsole console;

  public SonarLintTask(IssueProcessor processor, SonarLintJob job, boolean background) {
    this(processor, job, false, background);
  }

  /**
   * An IntelliJ task, that will run with its Progress Manager.
   * @param modal If true and background is false, it will be a blocking task, without the possibility to send it to background.
   * @param background Whether it should start in the foreground or background.
   */
  protected SonarLintTask(IssueProcessor processor, SonarLintJob job, boolean modal, boolean background) {
    super(job.project(), "SonarLint Analysis", true);
    this.processor = processor;
    this.job = job;
    this.modal = modal;
    this.startInBackground = background;
    this.console = SonarLintConsole.get(job.project());
  }

  @Override
  public boolean shouldStartInBackground() {
    return startInBackground;
  }

  @Override
  public boolean isConditionalModal() {
    return modal;
  }

  private static String getFileName(VirtualFile file) {
    return file.getName();
  }

  public SonarLintJob getJob() {
    return job;
  }

  @Override
  public void run(ProgressIndicator indicator) {
    AccumulatorIssueListener listener = new AccumulatorIssueListener();

    try {
      checkCanceled(indicator, myProject);

      List<AnalysisResults> results = analyze(myProject, indicator, listener);

      //last chance to cancel (to avoid the possibility of having interrupt flag set)
      checkCanceled(indicator, myProject);

      LOGGER.info("SonarLint analysis done");

      indicator.setIndeterminate(false);
      indicator.setFraction(.9);

      List<Issue> issues = listener.getIssues();
      indicator.setText("Creating SonarLint issues: " + issues.size());

      List<ClientInputFile> allFailedAnalysisFiles = results.stream()
        .flatMap(r -> r.failedAnalysisFiles().stream())
        .collect(Collectors.toList());

      processor.process(job, indicator, issues, allFailedAnalysisFiles);
    } catch (CanceledException e1) {
      console.info("Analysis canceled");
      return;
    } catch (RuntimeException e) {
      handleError(e, indicator);
    } finally {
      myProject.getMessageBus().syncPublisher(TaskListener.SONARLINT_TASK_TOPIC).ended(job);
    }
  }

  private void handleError(RuntimeException e, ProgressIndicator indicator) {
    // if cancelled, ignore any errors since they were most likely caused by the interrupt
    if (!indicator.isCanceled()) {
      String msg = "Error running SonarLint analysis";
      console.error(msg, e);
      LOGGER.warn(msg, e);

      if (indicator.isShowing()) {
        String dialogMsg = "SonarLint analysis failed: " + e.getMessage();
        ApplicationManager.getApplication().invokeAndWait(
          () -> Messages.showErrorDialog(dialogMsg, "Error Running SonarLint Analysis"), ModalityState.defaultModalityState());
      }

      AnalysisCallback callback = job.callback();
      if (callback != null) {
        callback.onError(e);
      }
    }
  }

  private static void checkCanceled(ProgressIndicator indicator, Project project) {
    if (indicator.isCanceled() || project.isDisposed()) {
      throw new CanceledException();
    }
  }

  private List<AnalysisResults> analyze(Project project, ProgressIndicator indicator, AccumulatorIssueListener listener) {
    SonarLintAnalyzer analyzer = SonarLintUtils.get(project, SonarLintAnalyzer.class);

    indicator.setIndeterminate(true);
    int numModules = job.filesPerModule().keySet().size();
    String suffix = "";
    if (numModules > 1) {
      suffix = String.format(" in %d modules", numModules);
    }

    int numFiles = job.allFiles().size();
    if (numFiles > 1) {
      indicator.setText("Running SonarLint Analysis for " + numFiles + " files" + suffix);
    } else {
      indicator.setText("Running SonarLint Analysis for '" + getFileName(job.allFiles().iterator().next()) + "'");
    }

    LOGGER.info(indicator.getText());

    CancelMonitor monitor = new CancelMonitor(indicator, Thread.currentThread());
    List<AnalysisResults> results = new LinkedList<>();

    try {
      monitor.start();
      for (Map.Entry<Module, Collection<VirtualFile>> e : job.filesPerModule().entrySet()) {
        results.add(analyzer.analyzeModule(e.getKey(), e.getValue(), listener));
        checkCanceled(indicator, myProject);
      }
      indicator.startNonCancelableSection();
    } finally {
      monitor.stopMonitor();
    }
    return results;
  }

  private class CancelMonitor extends Thread {
    private final ProgressIndicator indicator;
    private final Thread t;
    private boolean stop = false;

    private CancelMonitor(ProgressIndicator indicator, Thread t) {
      this.indicator = indicator;
      this.t = t;
      this.setName("sonarlint-cancel-monitor");
      this.setDaemon(true);
    }

    private synchronized void stopMonitor() {
      stop = true;
    }

    @Override
    public void run() {
      while (true) {
        synchronized (this) {
          // don't trust too much in isAlive: thread is probably pooled in a executor
          if (stop || !t.isAlive()) {
            return;
          }
        }
        if (indicator.isCanceled()) {
          // ensure that UI is canceled
          if (!indicator.isCanceled()) {
            indicator.cancel();
          }

          SonarLintConsole.get(myProject).info("Canceling...");
          t.interrupt();
          return;
        }

        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }
}
