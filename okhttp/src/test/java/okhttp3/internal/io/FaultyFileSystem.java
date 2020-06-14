/*
 * Copyright (C) 2011 The Android Open Source Project
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
package okhttp3.internal.io;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import okio.Buffer;
import okio.ForwardingSink;
import okio.Sink;
import okio.Source;
/** 模拟文件系统故障，如某一目录不可读写 */
public final class FaultyFileSystem implements FileSystem {
  private final FileSystem delegate;
  private final Set<File> writeFaults = new LinkedHashSet<>();
  /** 不可以删除的文件 */
  private final Set<File> deleteFaults = new LinkedHashSet<>();
  private final Set<File> renameFaults = new LinkedHashSet<>();

  public FaultyFileSystem(FileSystem delegate) {
    this.delegate = delegate;
  }

  public void setFaultyWrite(File file, boolean faulty) {
    if (faulty) {
      writeFaults.add(file);
    } else {
      writeFaults.remove(file);
    }
  }

  /**
   * 设置文件不可删除，删除会抛io异常
   * @param file 目标文件
   * @param faulty ture添加，false移除
   */
  public void setFaultyDelete(File file, boolean faulty) {
    if (faulty) {
      deleteFaults.add(file);
    } else {
      deleteFaults.remove(file);
    }
  }

  public void setFaultyRename(File file, boolean faulty) {
    if (faulty) {
      renameFaults.add(file);
    } else {
      renameFaults.remove(file);
    }
  }

  @Override public Source source(@NotNull File file) throws FileNotFoundException {
    return delegate.source(file);
  }

  @Override public Sink sink(@NotNull File file) throws FileNotFoundException {
    return new FaultySink(delegate.sink(file), file);
  }

  @Override public Sink appendingSink(@NotNull File file) throws FileNotFoundException {
    return new FaultySink(delegate.appendingSink(file), file);
  }

  @Override public void delete(@NotNull File file) throws IOException {
    if (deleteFaults.contains(file)) throw new IOException("boom!");
    delegate.delete(file);
  }

  @Override public boolean exists(@NotNull File file) {
    return delegate.exists(file);
  }

  @Override public long size(@NotNull File file) {
    return delegate.size(file);
  }

  @Override public void rename(@NotNull File from, @NotNull File to) throws IOException {
    if (renameFaults.contains(from) || renameFaults.contains(to)) throw new IOException("boom!");
    delegate.rename(from, to);
  }

  /** 删除文件 */
  @Override public void deleteContents(@NotNull File directory) throws IOException {
    if (deleteFaults.contains(directory)) throw new IOException("boom!");
    delegate.deleteContents(directory);
  }

  private class FaultySink extends ForwardingSink {
    private final File file;

    public FaultySink(Sink delegate, File file) {
      super(delegate);
      this.file = file;
    }

    @Override public void write(Buffer source, long byteCount) throws IOException {
      if (writeFaults.contains(file)) throw new IOException("boom!");
      super.write(source, byteCount);
    }
  }
}
