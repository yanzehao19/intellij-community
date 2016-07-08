/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.vcs.log.data.index;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.PerformInBackgroundOption;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.intellij.util.io.PersistentHashMap;
import com.intellij.util.io.PersistentMap;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.data.CommitDetailsGetter;
import com.intellij.vcs.log.data.InMemoryMap;
import com.intellij.vcs.log.data.TroveUtil;
import com.intellij.vcs.log.data.VcsUserRegistryImpl;
import com.intellij.vcs.log.ui.filter.VcsLogUserFilterImpl;
import com.intellij.vcs.log.util.PersistentUtil;
import gnu.trove.TIntHashSet;
import gnu.trove.TIntProcedure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VcsLogPersistentIndex implements VcsLogIndex, Disposable {
  private static final Logger LOG = Logger.getInstance(VcsLogPersistentIndex.class);
  private static final int BATCH_SIZE = 1000;

  @NotNull private final Project myProject;
  @NotNull private final Consumer<Exception> myFatalErrorsConsumer;
  @NotNull private final CommitDetailsGetter myDetailsGetter;
  @NotNull private final VcsLogStorage myHashMap;
  @NotNull private final VcsUserRegistryImpl myUserRegistry;
  @NotNull private final Set<VirtualFile> myRoots;

  @NotNull private final PersistentMap<Integer, String> myMessagesIndex;
  @Nullable private final VcsLogMessagesTrigramIndex myTrigramIndex;
  @Nullable private final VcsLogUserIndex myUserIndex;
  @Nullable private final VcsLogPathsIndex myPathsIndex;

  @NotNull private TIntHashSet myCommitsToIndex = new TIntHashSet();

  public VcsLogPersistentIndex(@NotNull Project project,
                               @NotNull VcsLogStorage hashMap,
                               @NotNull Map<VirtualFile, VcsLogProvider> providers,
                               @NotNull CommitDetailsGetter detailsGetter,
                               @NotNull Consumer<Exception> fatalErrorsConsumer,
                               @NotNull Disposable disposableParent) {
    myHashMap = hashMap;
    myProject = project;
    myDetailsGetter = detailsGetter;
    myFatalErrorsConsumer = fatalErrorsConsumer;
    myRoots = providers.keySet();

    myUserRegistry = (VcsUserRegistryImpl)ServiceManager.getService(myProject, VcsUserRegistry.class);

    String logId = PersistentUtil.calcLogId(myProject, providers);

    myMessagesIndex = createMap(EnumeratorStringDescriptor.INSTANCE, "messages", logId, 0);
    myTrigramIndex = createIndex(() -> new VcsLogMessagesTrigramIndex(logId, this));
    myUserIndex = createIndex(() -> new VcsLogUserIndex(logId, myUserRegistry, this));
    myPathsIndex = createIndex(() -> new VcsLogPathsIndex(logId, myRoots, this));

    Disposer.register(disposableParent, this);
  }

  @Nullable
  private <I extends VcsLogFullDetailsIndex> I createIndex(@NotNull ThrowableComputable<I, IOException> computable) {
    try {
      return computable.compute();
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(e);
    }
    return null;
  }

  @NotNull
  private <V> PersistentMap<Integer, V> createMap(@NotNull KeyDescriptor<V> descriptor,
                                                  @NotNull String kind,
                                                  @NotNull String logId, int version) {
    try {
      return PersistentUtil.createPersistentHashMap(descriptor, kind, logId, version);
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(e);
      return new InMemoryMap<>();
    }
  }

  @Override
  public synchronized void scheduleIndex() {
    if (myCommitsToIndex.isEmpty()) return;
    TIntHashSet commitsToIndex = myCommitsToIndex;
    myCommitsToIndex = new TIntHashSet();

    Task.Backgroundable task = new MyIndexingTask(commitsToIndex);

    ApplicationManager.getApplication().invokeLater(() -> {
                                                      BackgroundableProcessIndicator indicator = new BackgroundableProcessIndicator(task);
                                                      ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, indicator);
                                                    }
    );
  }

  private void index(@NotNull TIntHashSet commits) {
    try {
      storeDetails(TroveUtil.streamValues(myDetailsGetter.preLoadCommitData(commits, false)).collect(Collectors.toList()));
    }
    catch (VcsException e) {
      LOG.error(e);
      commits.forEach(value -> {
        markForIndexing(value);
        return true;
      });
    }
  }

  private void storeDetails(@NotNull Collection<VcsFullCommitDetails> details) {
    try {
      for (VcsFullCommitDetails detail : details) {
        int index = myHashMap.getCommitIndex(detail.getId(), detail.getRoot());

        myMessagesIndex.put(index, detail.getFullMessage());
        if (myTrigramIndex != null) myTrigramIndex.update(index, detail);
        if (myUserIndex != null) myUserIndex.update(index, detail);
        if (myPathsIndex != null) myPathsIndex.update(index, detail);
      }
      myMessagesIndex.force();
      if (myTrigramIndex != null) myTrigramIndex.flush();
      if (myUserIndex != null) myUserIndex.flush();
      if (myPathsIndex != null) myPathsIndex.flush();
    }
    catch (IOException | StorageException e) {
      myFatalErrorsConsumer.consume(e);
    }
  }

  public boolean isIndexed(int commit) {
    try {
      return myMessagesIndex.get(commit) != null &&
             (myUserIndex == null || myUserIndex.isIndexed(commit)) &&
             (myPathsIndex == null || myPathsIndex.isIndexed(commit)) &&
             (myTrigramIndex == null || myTrigramIndex.isIndexed(commit));
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(e);
    }
    return false;
  }

  @Override
  public synchronized void markForIndexing(int index) {
    myCommitsToIndex.add(index);
  }

  @NotNull
  private <T> TIntHashSet filter(@NotNull PersistentMap<Integer, T> map, @NotNull Condition<T> condition) {
    TIntHashSet result = new TIntHashSet();
    try {
      Processor<Integer> processor = integer -> {
        try {
          T value = map.get(integer);
          if (value != null) {
            if (condition.value(value)) {
              result.add(integer);
            }
          }
        }
        catch (IOException e) {
          myFatalErrorsConsumer.consume(e);
          return false;
        }
        return true;
      };
      if (myMessagesIndex instanceof PersistentHashMap) {
        ((PersistentHashMap<Integer, T>)myMessagesIndex).processKeysWithExistingMapping(processor);
      }
      else {
        myMessagesIndex.processKeys(processor);
      }
    }
    catch (IOException e) {
      myFatalErrorsConsumer.consume(e);
    }

    return result;
  }

  @NotNull
  private TIntHashSet filterUsers(@NotNull Set<VcsUser> users) {
    if (myUserIndex != null) {
      try {
        return myUserIndex.getCommitsForUsers(users);
      }
      catch (IOException | StorageException e) {
        myFatalErrorsConsumer.consume(e);
      }
    }
    return new TIntHashSet();
  }

  @NotNull
  private TIntHashSet filterPaths(@NotNull Collection<FilePath> paths) {
    if (myPathsIndex != null) {
      try {
        return myPathsIndex.getCommitsForPaths(paths);
      }
      catch (IOException | StorageException e) {
        myFatalErrorsConsumer.consume(e);
      }
    }
    return new TIntHashSet();
  }

  @NotNull
  public TIntHashSet filterMessages(@NotNull String text) {
    if (myTrigramIndex != null) {
      try {
        TIntHashSet commitsForSearch = myTrigramIndex.getCommitsForSubstring(text);
        if (commitsForSearch != null) {
          TIntHashSet result = new TIntHashSet();
          commitsForSearch.forEach(new TIntProcedure() {
            @Override
            public boolean execute(int commit) {
              try {
                String value = myMessagesIndex.get(commit);
                if (value != null) {
                  if (StringUtil.containsIgnoreCase(value, text)) {
                    result.add(commit);
                  }
                }
              }
              catch (IOException e) {
                myFatalErrorsConsumer.consume(e);
                return false;
              }

              return true;
            }
          });
          return result;
        }
      }
      catch (StorageException e) {
        myFatalErrorsConsumer.consume(e);
      }
    }

    return filter(myMessagesIndex, message -> StringUtil.containsIgnoreCase(message, text));
  }

  @Override
  public boolean canFilter(@NotNull List<VcsLogDetailsFilter> filters) {
    if (filters.isEmpty()) return false;
    for (VcsLogDetailsFilter filter : filters) {
      if (!((filter instanceof VcsLogTextFilter && myTrigramIndex != null) ||
            (filter instanceof VcsLogUserFilterImpl && myUserIndex != null) ||
            (filter instanceof VcsLogStructureFilter && myPathsIndex != null))) {
        return false;
      }
    }
    return true;
  }

  @Override
  @NotNull
  public Set<Integer> filter(@NotNull List<VcsLogDetailsFilter> detailsFilters) {
    VcsLogTextFilter textFilter = ContainerUtil.findInstance(detailsFilters, VcsLogTextFilter.class);
    VcsLogUserFilterImpl userFilter = ContainerUtil.findInstance(detailsFilters, VcsLogUserFilterImpl.class);
    VcsLogStructureFilter pathFilter = ContainerUtil.findInstance(detailsFilters, VcsLogStructureFilter.class);

    TIntHashSet filteredByMessage = null;
    if (textFilter != null) {
      filteredByMessage = filterMessages(textFilter.getText());
    }

    TIntHashSet filteredByUser = null;
    if (userFilter != null) {
      Set<VcsUser> users = ContainerUtil.newHashSet();
      for (VirtualFile root : myRoots) {
        users.addAll(userFilter.getUsers(root));
      }

      filteredByUser = filterUsers(users);
    }

    TIntHashSet filteredByPath = null;
    if (pathFilter != null) {
      filteredByPath = filterPaths(pathFilter.getFiles());
    }

    return TroveUtil.intersect(filteredByMessage, filteredByPath, filteredByUser);
  }

  @Override
  public void dispose() {
    try {
      myMessagesIndex.close();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  private class MyIndexingTask extends Task.Backgroundable {
    private final TIntHashSet myCommits;

    public MyIndexingTask(@NotNull TIntHashSet commits) {
      super(VcsLogPersistentIndex.this.myProject, "Indexing Commit Data", true, PerformInBackgroundOption.ALWAYS_BACKGROUND);
      myCommits = commits;
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
      indicator.setIndeterminate(false);

      Ref<Integer> nCommit = new Ref<>(0);
      Ref<Integer> indexedCommits = new Ref<>(0);
      LOG.info("Indexing " + myCommits.size() + " commits");

      long time = System.currentTimeMillis();
      Ref<TIntHashSet> commitsBatch = new Ref<>(new TIntHashSet());
      myCommits.forEach(commit -> {
        if (!isIndexed(commit)) {
          indexedCommits.set(indexedCommits.get() + 1);
          commitsBatch.get().add(commit);
          if (commitsBatch.get().size() >= BATCH_SIZE) {
            try {
              index(commitsBatch.get());
            }
            finally {
              commitsBatch.set(new TIntHashSet());
            }
          }
        }
        nCommit.set(nCommit.get() + 1);

        indicator.checkCanceled();
        indicator.setFraction(((double)nCommit.get()) / myCommits.size());
        return true;
      });

      if (!commitsBatch.get().isEmpty()) {
        index(commitsBatch.get());

        indicator.checkCanceled(); // TODO schedule for reindexing?
        indicator.setFraction(((double)nCommit.get() * BATCH_SIZE) / myCommits.size());
      }

      LOG.info((System.currentTimeMillis() - time) / 1000.0 + "sec for indexing " + indexedCommits.get() + " new commits");
    }
  }
}
