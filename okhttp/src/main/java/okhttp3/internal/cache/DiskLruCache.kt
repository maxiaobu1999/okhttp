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
package okhttp3.internal.cache

import okhttp3.internal.assertThreadHoldsLock
import okhttp3.internal.cache.DiskLruCache.Editor
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.Task
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.io.FileSystem
import okhttp3.internal.okHttpName
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.Platform.Companion.WARN
import okio.*
import java.io.*
import java.io.EOFException
import java.io.IOException
import java.util.*

/**
 * A cache that uses a bounded amount of space on a filesystem. Each cache entry has a string key
 * and a fixed number of values. Each key must match the regex `[a-z0-9_-]{1,64}`. Values are byte
 * sequences, accessible as streams or files. Each value must be between `0` and `Int.MAX_VALUE`
 * bytes in length.
 *
 * The cache stores its data in a directory on the filesystem. This directory must be exclusive to
 * the cache; the cache may delete or overwrite files from its directory. It is an error for
 * multiple processes to use the same cache directory at the same time.
 *
 * This cache limits the number of bytes that it will store on the filesystem. When the number of
 * stored bytes exceeds the limit, the cache will remove entries in the background until the limit
 * is satisfied. The limit is not strict: the cache may temporarily exceed it while waiting for
 * files to be deleted. The limit does not include filesystem overhead or the cache journal so
 * space-sensitive applications should set a conservative limit.
 *
 * Clients call [edit] to create or update the values of an entry. An entry may have only one editor
 * at one time; if a value is not available to be edited then [edit] will return null.
 *
 *  * When an entry is being **created** it is necessary to supply a full set of values; the empty
 *    value should be used as a placeholder if necessary.
 *
 *  * When an entry is being **edited**, it is not necessary to supply data for every value; values
 *    default to their previous value.
 *
 * Every [edit] call must be matched by a call to [Editor.commit] or [Editor.abort]. Committing is
 * atomic: a read observes the full set of values as they were before or after the commit, but never
 * a mix of values.
 *
 * Clients call [get] to read a snapshot of an entry. The read will observe the value at the time
 * that [get] was called. Updates and removals after the call do not impact ongoing reads.
 *
 * This class is tolerant of some I/O errors. If files are missing from the filesystem, the
 * corresponding entries will be dropped from the cache. If an error occurs while writing a cache
 * value, the edit will fail silently. Callers should handle other problems by catching
 * `IOException` and responding appropriately.
 *
 * @constructor Create a cache which will reside in [directory]. This cache is lazily initialized on
 *     first access and will be created if it does not exist.
 * @param directory a writable directory.
 * @param valueCount the number of values per cache entry. Must be positive.
 * @param maxSize the maximum number of bytes this cache should use to store.
 */
@Suppress("ConvertToStringTemplate")
class DiskLruCache internal constructor(
        internal val fileSystem: FileSystem,

        /** Returns the directory where this cache stores its data. */
        val directory: File,

        private val appVersion: Int,

        /** 默认2 */
        internal val valueCount: Int,

        /** Returns the maximum number of bytes that this cache should use to store its data. */
        maxSize: Long,

        /** Used for asynchronous journal rebuilds. */
        taskRunner: TaskRunner
) : Closeable, Flushable {
    /** The maximum number of bytes that this cache should use to store its data. */
    @get:Synchronized
    @set:Synchronized
    var maxSize: Long = maxSize
        set(value) {
            field = value
            if (initialized) {
                cleanupQueue.schedule(cleanupTask) // Trim the existing store if necessary.
            }
        }

    /*
     * This cache uses a journal file named "journal". A typical journal file looks like this:
     *
     *     libcore.io.DiskLruCache
     *     1
     *     100
     *     2
     *
     *     CLEAN 3400330d1dfc7f3f7f4b8d4d803dfcf6 832 21054
     *     DIRTY 335c4c6028171cfddfbaae1a9c313c52
     *     CLEAN 335c4c6028171cfddfbaae1a9c313c52 3934 2342
     *     REMOVE 335c4c6028171cfddfbaae1a9c313c52
     *     DIRTY 1ab96a171faeeee38496d8b330771a7a
     *     CLEAN 1ab96a171faeeee38496d8b330771a7a 1600 234
     *     READ 335c4c6028171cfddfbaae1a9c313c52
     *     READ 3400330d1dfc7f3f7f4b8d4d803dfcf6
     *
     * The first five lines of the journal form its header. They are the constant string
     * "libcore.io.DiskLruCache", the disk cache's version, the application's version, the value
     * count, and a blank line.
     *
     * Each of the subsequent lines in the file is a record of the state of a cache entry. Each line
     * contains space-separated values: a state, a key, and optional state-specific values.
     *
     *   o DIRTY lines track that an entry is actively being created or updated. Every successful
     *     DIRTY action should be followed by a CLEAN or REMOVE action. DIRTY lines without a matching
     *     CLEAN or REMOVE indicate that temporary files may need to be deleted.
     *
     *   o CLEAN lines track a cache entry that has been successfully published and may be read. A
     *     publish line is followed by the lengths of each of its values.
     *
     *   o READ lines track accesses for LRU.
     *
     *   o REMOVE lines track entries that have been deleted.
     *
     * The journal file is appended to as cache operations occur. The journal may occasionally be
     * compacted by dropping redundant lines. A temporary file named "journal.tmp" will be used during
     * compaction; that file should be deleted if it exists when the cache is opened.
     */

    /** 日志文件（保存），是对缓存一系列操作的记录，不影响缓存的执行流程。 */
    private val journalFile: File
    private val journalFileTmp: File

    /** 日志文件（备份） */
    private val journalFileBackup: File
    private var size: Long = 0L
    /** 用于将流写入日志文件 */
    private var journalWriter: BufferedSink? = null

    /** LinkedHashMap自带Lru算法的光环属性 */
    internal val lruEntries = LinkedHashMap<String, Entry>(0, 0.75f, true)
    private var redundantOpCount: Int = 0
    private var hasJournalErrors: Boolean = false

    // 初始化过的标识。Must be read and written when synchronized on 'this'.
    private var initialized: Boolean = false
    internal var closed: Boolean = false
    private var mostRecentTrimFailed: Boolean = false
    private var mostRecentRebuildFailed: Boolean = false

    /**
     * To differentiate between old and current snapshots, each entry is given a sequence number each
     * time an edit is committed. A snapshot is stale if its sequence number is not equal to its
     * entry's sequence number.
     */
    private var nextSequenceNumber: Long = 0

    private val cleanupQueue = taskRunner.newQueue()

    /** 清理缓存的线程 */
    private val cleanupTask = object : Task("$okHttpName Cache") {
        /**
         * 1.如果还没有初始化或者缓存关闭了，则不清理。
         * 2.执行清理操作。
         * 3.如果清理完了还是判断后还需要清理，只能重新构建日志文件，并且日志记录器记0。
         */
        override fun runOnce(): Long {
            synchronized(this@DiskLruCache) {
                // 如果没有初始化或者已经关闭了，则不需要清理，这里注意|和||的区别，|会两个条件都检查
                if (!initialized || closed) {
                    return -1L // Nothing to do.
                }

                try {
                    // 清理
                    trimToSize()
                } catch (_: IOException) {
                    mostRecentTrimFailed = true
                }

                try {
                    if (journalRebuildRequired()) {
                        // 如果还要清理，重新构建
                        rebuildJournal()
                        redundantOpCount = 0 //计数器置0
                    }
                } catch (_: IOException) {
                    // 如果抛异常了，设置最近的一次构建失败
                    mostRecentRebuildFailed = true
                    journalWriter = blackholeSink().buffer()
                }

                return -1L
            }
        }
    }

    init {
        require(maxSize > 0L) { "maxSize <= 0" }
        require(valueCount > 0) { "valueCount <= 0" }

        this.journalFile = File(directory, JOURNAL_FILE)
        this.journalFileTmp = File(directory, JOURNAL_FILE_TEMP)
        this.journalFileBackup = File(directory, JOURNAL_FILE_BACKUP)
    }

    /**
     * 初始化
     * 1.这个方法线程安全
     * 2.如果初始化过了，则什么都不干，只初始化一遍
     * 3.如果有journalFile日志文件，则对journalFile文件和lruEntries进行初始化操作，主要是删除冗余信息，和DIRTY信息。
     * 4.没有则构建一个journalFile文件。
     *
     *
     * 总结下：
    获取一个写入流，将lruEntries集合中的Entry对象写入tmp文件中，根据Entry的currentEditor的值判断是CLEAN还是DIRTY,写入该Entry的key，如果是CLEAN还要写入文件的大小bytes。然后就是把journalFileTmp更名为journalFile，然后将journalWriter跟文件绑定，通过它来向journalWrite写入数据，最后设置一些属性。
    我们可以砍到，rebuild操作是以lruEntries为准，把DIRTY和CLEAN的操作都写回到journal中。但发现没有，其实没有改动真正的value，只不过重写了一些事务的记录。事实上，lruEntries和journal文件共同确定了cache数据的有效性。lruEntries是索引，journal是归档。至此序列化部分就已经结束了
     */
    @Synchronized
    @Throws(IOException::class)
    fun initialize() {
        // 断言，当持有自己锁的时候。继续执行，没有持有锁，直接抛异常
        this.assertThreadHoldsLock()

        if (initialized) {
            return // 已经初始化过，则不需要再初始化.
        }

        // If a bkp file exists, use it instead.
        // 如果有journalFileBackup这个文件
        if (fileSystem.exists(journalFileBackup)) {
            // If journal file also exists just delete backup file.
            // 如果有journalFile这个文件
            if (fileSystem.exists(journalFile)) {
                // 删除journalFileBackup这个文件
                fileSystem.delete(journalFileBackup)
            } else {
                //没有journalFile这个文件，并且有journalFileBackup这个文件，则将journalFileBackup改名为journalFile
                fileSystem.rename(journalFileBackup, journalFile)
            }
        }
        // 最后的结果只有两种：1.什么都没有2.有journalFile文件
        // Prefer to pick up where we left off.
        if (fileSystem.exists(journalFile)) {
            // 如果有journalFile文件
            try {
                readJournal()
                processJournal()
                // 标记初始化完成
                initialized = true
                return
            } catch (journalIsCorrupt: IOException) {
                Platform.get().log(
                        "DiskLruCache $directory is corrupt: ${journalIsCorrupt.message}, removing",
                        WARN,
                        journalIsCorrupt)
            }

            // The cache is corrupted, attempt to delete the contents of the directory. This can throw and
            // we'll let that propagate out as it likely means there is a severe filesystem problem.
            try {
                // 有缓存损坏导致异常，则删除缓存目录下所有文件
                delete()
            } finally {
                closed = false
            }
        }
        // 如果没有则重新创建一个
        rebuildJournal()
        // 标记初始化完成,无论有没有journal文件，initialized都会标记为true，只执行一遍
        initialized = true
    }

    /**
     * ource.readUtf8LineStrict()方法，这个方法是BufferedSource接口的方法，具体实现是RealBufferedSource，所以大家要去RealBufferedSource里面去找具体实现。我这里简单说下，就是从source里面按照utf-8编码取出一行的数据。这里面读取了magic，version，appVersionString，valueCountString，blank，然后进行校验，这个数据是在"写"的时候，写入的，具体情况看DiskLruCache的rebuildJournal()方法。随后记录redundantOpCount的值，该值的含义就是判断当前日志中记录的行数和lruEntries集合容量的差值，即日志中多出来的"冗余"记录。
     */
    @Throws(IOException::class)
    private fun readJournal() {
        // 利用Okio读取journalFile文件
        // 获取journalFile的source即输入流
        fileSystem.source(journalFile).buffer().use { source ->
            //读取相关数据
            val magic = source.readUtf8LineStrict()
            val version = source.readUtf8LineStrict()
            val appVersionString = source.readUtf8LineStrict()
            val valueCountString = source.readUtf8LineStrict()
            val blank = source.readUtf8LineStrict()
            // 做校验，保证和默认值相同
            if (MAGIC != magic ||
                    VERSION_1 != version ||
                    appVersion.toString() != appVersionString ||
                    valueCount.toString() != valueCountString ||
                    blank.isNotEmpty()) {
                throw IOException(
                        "unexpected journal header: [$magic, $version, $valueCountString, $blank]")
            }

            var lineCount = 0
            // 校验通过，开始逐行读取数据
            while (true) {
                try {
                    // 逐行读取，并根据每行的开头，不同的状态执行不同的操作，主要就是往lruEntries里面add，或者remove
                    readJournalLine(source.readUtf8LineStrict())
                    lineCount++
                } catch (_: EOFException) {
                    break // End of journal.
                }
            }
            // 日志操作的记录数=总行数-lruEntries中实际add的行数
            redundantOpCount = lineCount - lruEntries.size

            // If we ended on a truncated line, rebuild the journal before appending to it.
            if (!source.exhausted()) {
                // 如果有多余的字节，则重新构建下journal文件
                // 如果有多余字节，则重新构建下journal文件，主要是写入头文件，以便下次读的时候，根据头文件进行校验
                rebuildJournal()
            } else {
                // 获取这个文件的Sink,以便Writer
                journalWriter = newJournalWriter()
            }
        }
    }

    @Throws(FileNotFoundException::class)
    private fun newJournalWriter(): BufferedSink {
        val fileSink = fileSystem.appendingSink(journalFile)
        val faultHidingSink = FaultHidingSink(fileSink) {
            this@DiskLruCache.assertThreadHoldsLock()
            hasJournalErrors = true
        }
        return faultHidingSink.buffer()
    }

    /**
     * 如果每次解析的是非REMOVE信息，利用该key创建一个entry，如果是判断信息是CLEAN则设置ENTRY为可读，并设置entry.currentEditor表明当前Entry不可编辑，调用entry.setLengths(String[])，设置该entry.lengths的初始值。如果判断是Dirty则设置enry.currentEdtor=new Editor(entry)；表明当前Entry处于被编辑状态。
     *
     * 1、如果是CLEAN的话，对这个entry的文件长度进行更新
     *     2、如果是DIRTY，说明这个值正在被操作，还没有commit，于是给entry分配一个Editor。
     *         3、如果是READ，说明这个值被读过了，什么也不做。
     */
    @Throws(IOException::class)
    private fun readJournalLine(line: String) {
        // 记录第一个空串的位置 获取空串的position，表示头
        val firstSpace = line.indexOf(' ')
        //空串的校验
        if (firstSpace == -1) throw IOException("unexpected journal line: $line")

        //第一个字符的位置
        val keyBegin = firstSpace + 1
        // 记录第二个空串的位置
        // 方法返回第一个空字符在此字符串中第一次出现，在指定的索引即keyBegin开始搜索，所以secondSpace是爱这个字符串中的空字符(不包括这一行最左侧的那个空字符)
        val secondSpace = line.indexOf(' ', keyBegin)
        val key: String
        //如果没有中间的空字符
        if (secondSpace == -1) {
            // 截取剩下的全部字符串构成key； 如果中间没有空串，则直接截取得到key
            key = line.substring(keyBegin)
            // 如果解析出来的是"REMOVE skjdglajslkgjl"这样以REMOVE开头
            if (firstSpace == REMOVE.length && line.startsWith(REMOVE)) {
                // 移除这个key，lruEntries是LinkedHashMap
                //如果解析的是REMOVE信息，则在lruEntries里面删除这个key
                lruEntries.remove(key)
                return
            }
        } else {
            // 解析两个空格间的字符串为key
            //如果含有中间间隔的空字符，则截取这个中间间隔到左侧空字符之间的字符串，构成key
            key = line.substring(keyBegin, secondSpace)
        }
        //获取key后，根据key取出Entry对象
        var entry: Entry? = lruEntries[key]
        //如果Entry为null，则表明内存中没有，则new一个，并把它放到内存中。
        if (entry == null) {
            // new一个Entry，put进去
            entry = Entry(key)
            lruEntries[key] = entry
        }

        when {
            // 如果是“CLEAN 1 2”这样的以CLAEN开头
            secondSpace != -1 && firstSpace == CLEAN.length && line.startsWith(CLEAN) -> {
                //line.substring(secondSpace + 1) 为获取中间空格后面的内容，然后按照空字符分割，设置entry的属性，表明是干净的数据，不能编辑。
                // 取第二个空格后面的字符串，parts变成[1,2]
                val parts = line.substring(secondSpace + 1)
                        .split(' ')
                // 可读
                entry.readable = true
                // 不被编辑
                entry.currentEditor = null
                // 设置长度
                entry.setLengths(parts)
            }
            // 如果是“DIRTY lskdjfkl”这样以DIRTY开头，新建一个Editor
            //如果是以DIRTY开头，则设置一个新的Editor，表明可编辑
            secondSpace == -1 && firstSpace == DIRTY.length && line.startsWith(DIRTY) -> {
                entry.currentEditor = Editor(entry)
            }
            // 如果是“READ slkjl”这样以READ开头，不需要做什么事
            secondSpace == -1 && firstSpace == READ.length && line.startsWith(READ) -> {
                // This work was already done by calling lruEntries.get().
            }

            else -> throw IOException("unexpected journal line: $line")
        }
    }

    /**
     * 处理日志文件
     * Computes the initial size and collects garbage as a part of opening the cache. Dirty entries
     * are assumed to be inconsistent and will be deleted.
     * 先是删除了journalFileTmp文件
    然后调用for循环获取链表中的所有Entry，如果Entry的中Editor!=null，则表明Entry数据时脏的DIRTY，所以不能读，进而删除Entry下的缓存文件，并且将Entry从lruEntries中移除。如果Entry的Editor==null，则证明该Entry下的缓存文件可用，记录它所有缓存文件的缓存数量，结果赋值给size。
     */
    @Throws(IOException::class)
    private fun processJournal() {
        // 删除journalFileTmp文件
        fileSystem.delete(journalFileTmp)
        val i = lruEntries.values.iterator()
        while (i.hasNext()) {
            val entry = i.next()
            if (entry.currentEditor == null) {
                // 表明数据是CLEAN,循环记录SIZE
                for (t in 0 until valueCount) {
                    size += entry.lengths[t]
                }
            } else {
                // 表明数据是DIRTY，删除
                entry.currentEditor = null
                for (t in 0 until valueCount) {
                    fileSystem.delete(entry.cleanFiles[t])
                    fileSystem.delete(entry.dirtyFiles[t])
                }
                // 移除Entry
                i.remove()
            }
        }
    }

    /**
     * Creates a new journal that omits redundant information. This replaces the current journal if it
     * exists.
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun rebuildJournal() {
        //如果写入流不为空，关闭写入流
        journalWriter?.close()

        //通过okio获取一个写入BufferedSinke
        fileSystem.sink(journalFileTmp).buffer().use { sink ->
            // 写入校验信息
            //写入相关信息和读取向对应，这时候大家想下readJournal
            sink.writeUtf8(MAGIC).writeByte('\n'.toInt())
            sink.writeUtf8(VERSION_1).writeByte('\n'.toInt())
            sink.writeDecimalLong(appVersion.toLong()).writeByte('\n'.toInt())
            sink.writeDecimalLong(valueCount.toLong()).writeByte('\n'.toInt())
            sink.writeByte('\n'.toInt())

            // 利用刚才逐行读的内容按照格式重新构建
            //遍历lruEntries里面的值
            for (entry in lruEntries.values) {
                if (entry.currentEditor != null) {
                    //如果editor不为null，则为DIRTY数据
                    sink.writeUtf8(DIRTY).writeByte(' '.toInt()) // 在开头写上 DIRTY，然后写上 空字符
                    sink.writeUtf8(entry.key)  // 把entry的key写上
                    sink.writeByte('\n'.toInt())// 换行
                } else {
                    //如果editor为null，则为CLEAN数据,  在开头写上 CLEAN，然后写上 空字符
                    sink.writeUtf8(CLEAN).writeByte(' '.toInt())
                    //把entry的key写上
                    sink.writeUtf8(entry.key)
                    //结尾接上两个十进制的数字，表示长度
                    entry.writeLengths(sink)
                    sink.writeByte('\n'.toInt())//换行
                }
            }
        }

        //如果存在journalFile
        // 用新构建的journalFileTmp替换当前的journalFile文件
        if (fileSystem.exists(journalFile)) {
            //把journalFile文件重命名为journalFileBackup
            fileSystem.rename(journalFile, journalFileBackup)
        }
        //然后又把临时文件，重命名为journalFile
        fileSystem.rename(journalFileTmp, journalFile)
        //删除备份文件
        fileSystem.delete(journalFileBackup)

        journalWriter = newJournalWriter()//拼接一个新的写入流
        hasJournalErrors = false//设置没有error标志
        mostRecentRebuildFailed = false//设置最近重新创建journal文件成功
    }

    /**
     * Returns a snapshot of the entry named [key], or null if it doesn't exist is not currently
     * readable. If a value is returned, it is moved to the head of the LRU queue.
     */
    /**
     * 获取DiskLruCache.Snapshot
     * 1.初始化日志文件和lruEntries
     *    2.检查保证key正确后获取缓存中保存的Entry。
     *3.操作计数器+1
     *  4.往日志文件中写入这次的READ操作。
     *5.根据redundantOpCount判断是否需要清理日志信息。
     *6.需要则开启线程清理。
     *7.不需要则返回缓存。
     */
    @Synchronized
    @Throws(IOException::class)
    operator fun get(key: String): Snapshot? {
        // 对journalFile文件的操作，有则删除无用冗余的信息，构建新文件，没有则new一个新的
        initialize()
        // 判断是否关闭，如果缓存损坏了，会被关闭
        checkNotClosed()
        // 检查key是否满足格式要求，正则表达式
        validateKey(key)
        // 获取key对应的entry
        val entry = lruEntries[key] ?: return null
        if (!entry.readable) return null
        // 获取entry里面的snapshot的值
        val snapshot = entry.snapshot() ?: return null
        // 有则计数器+1
        redundantOpCount++
        // 把这个内容写入文档中
        journalWriter!!.writeUtf8(READ)
                .writeByte(' '.toInt())
                .writeUtf8(key)
                .writeByte('\n'.toInt())
        // 判断是否达清理条件
        if (journalRebuildRequired()) {
            cleanupQueue.schedule(cleanupTask)
        }

        return snapshot
    }

    /** Returns an editor for the entry named [key], or null if another edit is in progress. */
    /**
     * @param expectedSequenceNumber 预期序列号,默认值：ANY_SEQUENCE_NUMBER  任何序列号
     * 
     */
    @Synchronized
    @Throws(IOException::class)
    @JvmOverloads
    fun edit(key: String, expectedSequenceNumber: Long = ANY_SEQUENCE_NUMBER): Editor? {
        initialize()// 初始化

        checkNotClosed()
        validateKey(key)
        var entry: Entry? = lruEntries[key]// 缓存里查一下

        if (expectedSequenceNumber != ANY_SEQUENCE_NUMBER &&
                (entry == null || entry.sequenceNumber != expectedSequenceNumber)) {
            return null // 快照已过时.
        }

        if (entry?.currentEditor != null) {
            return null // 另一个 edit 正在编辑.
        }

        if (mostRecentTrimFailed || mostRecentRebuildFailed) {
            // The OS has become our enemy! If the trim job failed, it means we are storing more data than
            // requested by the user. Do not allow edits so we do not go over that limit any further. If
            // the journal rebuild failed, the journal writer will not be active, meaning we will not be
            // able to record the edit, causing file leaks. In both cases, we want to retry the clean up
            // so we can get out of this state!
            //操作系统已成为我们的敌人！ 如果修剪作业失败，则意味着我们存储的数据量超出了用户的要求。 不允许编辑，因此我们不会再超过该限制。 如果日志重建失败，则日记记录作者将不会处于活动状态，这意味着我们将无法记录编辑，从而导致文件泄漏。 在这两种情况下，我们都想重试清理，以便摆脱这种状态！
            cleanupQueue.schedule(cleanupTask)
            return null
        }

        // 这个key，写个空日志
        val journalWriter = this.journalWriter!!
//        println("【DiskLruCache】" + "edit():journalWriter=" + journalWriter)
        journalWriter.writeUtf8(DIRTY)
                .writeByte(' '.toInt())
                .writeUtf8(key)
                .writeByte('\n'.toInt())
        journalWriter.flush()

        if (hasJournalErrors) {
            //不要编辑； 该日志不能写。
            return null
        }

        // 内存没有，创建新的日志缓存类
        if (entry == null) {
            entry = Entry(key)
            lruEntries[key] = entry
        }
        val editor = Editor(entry)
        entry.currentEditor = editor// 缓存类 成员变量编辑器
        return editor
    }

    /**
     * Returns the number of bytes currently being used to store the values in this cache. This may be
     * greater than the max size if a background deletion is pending.
     */
    @Synchronized
    @Throws(IOException::class)
    fun size(): Long {
        initialize()
        return size
    }

    @Synchronized
    @Throws(IOException::class)
    internal fun completeEdit(editor: Editor, success: Boolean) {
        val entry = editor.entry
        check(entry.currentEditor == editor)

        // If this edit is creating the entry for the first time, every index must have a value.
        if (success && !entry.readable) {
            for (i in 0 until valueCount) {
                if (!editor.written!![i]) {
                    editor.abort()
                    throw IllegalStateException("Newly created entry didn't create value for index $i")
                }
                if (!fileSystem.exists(entry.dirtyFiles[i])) {
                    editor.abort()
                    return
                }
            }
        }

        for (i in 0 until valueCount) {
            val dirty = entry.dirtyFiles[i]
            if (success) {
                if (fileSystem.exists(dirty)) {
                    val clean = entry.cleanFiles[i]
                    fileSystem.rename(dirty, clean)
                    val oldLength = entry.lengths[i]
                    val newLength = fileSystem.size(clean)
                    entry.lengths[i] = newLength
                    size = size - oldLength + newLength
                }
            } else {
                fileSystem.delete(dirty)
            }
        }

        redundantOpCount++
        entry.currentEditor = null
        journalWriter!!.apply {
            if (entry.readable || success) {
                entry.readable = true
                writeUtf8(CLEAN).writeByte(' '.toInt())
                writeUtf8(entry.key)
                entry.writeLengths(this)
                writeByte('\n'.toInt())
                if (success) {
                    entry.sequenceNumber = nextSequenceNumber++
                }
            } else {
                lruEntries.remove(entry.key)
                writeUtf8(REMOVE).writeByte(' '.toInt())
                writeUtf8(entry.key)
                writeByte('\n'.toInt())
            }
            flush()
        }

        if (size > maxSize || journalRebuildRequired()) {
            cleanupQueue.schedule(cleanupTask)
        }
    }

    /**
     * We only rebuild the journal when it will halve the size of the journal and eliminate at least
     * 2000 ops.
     * 判断是否需要清理缓存
     */
    private fun journalRebuildRequired(): Boolean {
        val redundantOpCompactThreshold = 2000
        // 清理的条件是当前redundantOpCount大于2000，并且redundantOpCount的值大于linkedList里面的size
        return redundantOpCount >= redundantOpCompactThreshold &&
                redundantOpCount >= lruEntries.size
    }

    /**
     * 移除请求
     * Drops the entry for [key] if it exists and can be removed. If the entry for [key] is currently
     * being edited, that edit will complete normally but its value will not be stored.
     *
     * @return true if an entry was removed.
     */
    @Synchronized
    @Throws(IOException::class)
    fun remove(key: String): Boolean {
        initialize()

        checkNotClosed()
        validateKey(key)
        val entry = lruEntries[key] ?: return false
        val removed = removeEntry(entry)
        if (removed && size <= maxSize) mostRecentTrimFailed = false
        return removed
    }

    @Throws(IOException::class)
    internal fun removeEntry(entry: Entry): Boolean {
        // 结束editor
        entry.currentEditor?.detach() // Prevent the edit from completing normally.

        for (i in 0 until valueCount) {
            // 清除用于保存文件的cleanFiles
            fileSystem.delete(entry.cleanFiles[i])
            size -= entry.lengths[i]
            entry.lengths[i] = 0
        }

        // 计数器加1
        redundantOpCount++
        // 增加一条删除日志
        journalWriter!!.writeUtf8(REMOVE)
                .writeByte(' '.toInt())
                .writeUtf8(entry.key)
                .writeByte('\n'.toInt())
        //移除entry
        lruEntries.remove(entry.key)

        //如果需要重新清理一下，边界情况
        if (journalRebuildRequired()) {
            cleanupQueue.schedule(cleanupTask)
        }

        return true
    }

    @Synchronized
    private fun checkNotClosed() {
        check(!closed) { "cache is closed" }
    }

    /** Force buffered operations to the filesystem. */
    @Synchronized
    @Throws(IOException::class)
    override fun flush() {
        if (!initialized) return

        checkNotClosed()
        trimToSize()
        journalWriter!!.flush()
    }

    @Synchronized
    fun isClosed(): Boolean = closed

    /** Closes this cache. Stored values will remain on the filesystem. */
    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (!initialized || closed) {
            closed = true
            return
        }

        // Copying for concurrent iteration.
        for (entry in lruEntries.values.toTypedArray()) {
            if (entry.currentEditor != null) {
                entry.currentEditor!!.abort()
            }
        }

        trimToSize()
        journalWriter!!.close()
        journalWriter = null
        closed = true
    }

    @Throws(IOException::class)
    fun trimToSize() {
        // 遍历直到满足大小
        while (size > maxSize) {
            val toEvict = lruEntries.values.iterator().next()
            removeEntry(toEvict)
        }
        mostRecentTrimFailed = false
    }

    /**
     * Closes the cache and deletes all of its stored values. This will delete all files in the cache
     * directory including files that weren't created by the cache.
     */
    @Throws(IOException::class)
    fun delete() {
        close()
        fileSystem.deleteContents(directory)
    }

    /**
     * Deletes all stored values from the cache. In-flight edits will complete normally but their
     * values will not be stored.
     */
    @Synchronized
    @Throws(IOException::class)
    fun evictAll() {
        initialize()
        // Copying for concurrent iteration.
        for (entry in lruEntries.values.toTypedArray()) {
            removeEntry(entry)
        }
        mostRecentTrimFailed = false
    }

    /** 验证KEY的格式 */
    private fun validateKey(key: String) {
        require(LEGAL_KEY_PATTERN.matches(key)) { "keys must match regex [a-z0-9_-]{1,120}: \"$key\"" }
    }

    /**
     * Returns an iterator over the cache's current entries. This iterator doesn't throw
     * `ConcurrentModificationException`, but if new entries are added while iterating, those new
     * entries will not be returned by the iterator. If existing entries are removed during iteration,
     * they will be absent (unless they were already returned).
     *
     * If there are I/O problems during iteration, this iterator fails silently. For example, if the
     * hosting filesystem becomes unreachable, the iterator will omit elements rather than throwing
     * exceptions.
     *
     * **The caller must [close][Snapshot.close]** each snapshot returned by [Iterator.next]. Failing
     * to do so leaks open files!
     */
    @Synchronized
    @Throws(IOException::class)
    fun snapshots(): MutableIterator<Snapshot> {
        initialize()
        return object : MutableIterator<Snapshot> {
            /** Iterate a copy of the entries to defend against concurrent modification errors. */
            val delegate = ArrayList(lruEntries.values).iterator()

            /** The snapshot to return from [next]. Null if we haven't computed that yet. */
            var nextSnapshot: Snapshot? = null

            /** The snapshot to remove with [remove]. Null if removal is illegal. */
            var removeSnapshot: Snapshot? = null

            override fun hasNext(): Boolean {
                if (nextSnapshot != null) return true

                synchronized(this@DiskLruCache) {
                    // If the cache is closed, truncate the iterator.
                    if (closed) return false

                    while (delegate.hasNext()) {
                        val entry = delegate.next()
                        if (entry == null || !entry.readable) continue // Entry during edit

                        val snapshot = entry.snapshot() ?: continue
                        // Evicted since we copied the entries.
                        nextSnapshot = snapshot
                        return true
                    }
                }

                return false
            }

            override fun next(): Snapshot {
                if (!hasNext()) throw NoSuchElementException()
                removeSnapshot = nextSnapshot
                nextSnapshot = null
                return removeSnapshot!!
            }

            override fun remove() {
                val removeSnapshot = this.removeSnapshot
                checkNotNull(removeSnapshot) { "remove() before next()" }
                try {
                    this@DiskLruCache.remove(removeSnapshot.key())
                } catch (_: IOException) {
                    // Nothing useful to do here. We failed to remove from the cache. Most likely that's
                    // because we couldn't update the journal, but the cached entry will still be gone.
                } finally {
                    this.removeSnapshot = null
                }
            }
        }
    }

    /** A snapshot of the values for an entry. */
    inner class Snapshot internal constructor(
            private val key: String,
            /** 序列号 */
            private val sequenceNumber: Long,
            /** 可以读入数据的流   这么多的流主要是从cleanFile中读取数据 */
            private val sources: List<Source>,
            /** 与上面的流一一对应   */
            private val lengths: LongArray
    ) : Closeable {
        fun key(): String = key

        /**
         * Returns an editor for this snapshot's entry, or null if either the entry has changed since
         * this snapshot was created or if another edit is in progress.
         * 获得一个DiskLruCache.Editor对象，
         */
        // edit方法主要就是调用DiskLruCache的edit方法了，入参是该Snapshot对象的两个属性key和sequenceNumber.
        @Throws(IOException::class)
        fun edit(): Editor? = this@DiskLruCache.edit(key, sequenceNumber)

        /** Returns the unbuffered stream with the value for [index]. */
        // 获取一个Source对象。 (具体看Editor类)
        fun getSource(index: Int): Source = sources[index]

        /** Returns the byte length of the value for [index]. */
        fun getLength(index: Int): Long = lengths[index]

        override fun close() {
            for (source in sources) {
                source.closeQuietly()
            }
        }
    }

    /** Edits the values for an entry. */
    /**
     * Entry的编辑器
     * abort()和abortUnlessCommitted()最后都会执行completeEdit(Editor, boolean) 这个方法这里简单说下：
     *   success情况提交：dirty文件会被更名为clean文件，entry.lengths[i]值会被更新，DiskLruCache,size会更新（DiskLruCache,size代表的是所有整个缓存文件加起来的总大小），redundantOpCount++，在日志中写入一条Clean信息
     *   failed情况：dirty文件被删除，redundantOpCount++，日志中写入一条REMOVE信息
     */
    inner class Editor internal constructor(internal val entry: Entry) {
        internal val written: BooleanArray? = if (entry.readable) null else BooleanArray(valueCount)
        private var done: Boolean = false

        /**
         * Prevents this editor from completing normally. This is necessary either when the edit causes
         * an I/O error, or if the target entry is evicted while this editor is active. In either case
         * we delete the editor's created files and prevent new files from being created. Note that once
         * an editor has been detached it is possible for another editor to edit the entry.
         *
         *这里说一下detach方法，当编辑器(Editor)处于io操作的error的时候，或者editor正在被调用的时候而被清
         *除的，为了防止编辑器可以正常的完成。我们需要删除编辑器创建的文件，并防止创建新的文件。如果编
         *辑器被分离，其他的编辑器可以编辑这个Entry
         */
        internal fun detach() {
            if (entry.currentEditor == this) {
                for (i in 0 until valueCount) {
                    try {
                        fileSystem.delete(entry.dirtyFiles[i])
                    } catch (_: IOException) {
                        // This file is potentially leaked. Not much we can do about that.
                    }
                }
                entry.currentEditor = null
            }
        }

        /**
         * Returns an unbuffered input stream to read the last committed value, or null if no value has
         * been committed.
         * 返回指定index的cleanFile的读入流
         * 获取cleanFile的输入流 在commit的时候把done设为true
         */
        fun newSource(index: Int): Source? {
            synchronized(this@DiskLruCache) {
                //如果已经commit了，不能读取了
                check(!done)
                //如果entry不可读，并且已经有编辑器了(其实就是dirty)
                if (!entry.readable || entry.currentEditor != this) {
                    return null
                }
                return try {
                    //通过filesystem获取cleanFile的输入流
                    fileSystem.source(entry.cleanFiles[index])
                } catch (_: FileNotFoundException) {
                    null
                }
            }
        }

        /**
         * Returns a new unbuffered output stream to write the value at [index]. If the underlying
         * output stream encounters errors when writing to the filesystem, this edit will be aborted
         * when [commit] is called. The returned output stream does not throw IOExceptions.
         *
         * 获得一个sink (outputStream)
         * <p>
         * 向指定index的dirtyFile文件输出流
         * 如果在写入数据的时候出现错误，会立即停止。返回的输出流不会抛IO异常
         */
        fun newSink(index: Int): Sink {
            synchronized(this@DiskLruCache) {
                //已经提交，不能操作
                check(!done)
                // 如果编辑器与缓存类不对应，不能操作
                if (entry.currentEditor != this) {
                    return blackholeSink()
                }
                // 如果entry不可读，把对应的written设为true
                if (!entry.readable) {
                    written!![index] = true
                }
                val dirtyFile = entry.dirtyFiles[index]
                val sink: Sink
                try {
                    // fileSystem获取文件的输出流
                    sink = fileSystem.sink(dirtyFile)
                } catch (_: FileNotFoundException) {
                    return blackholeSink()
                }
                return FaultHidingSink(sink) {
                    synchronized(this@DiskLruCache) {
                        detach()
                    }
                }
            }
        }

        /**
         * Commits this edit so it is visible to readers. This releases the edit lock so another edit
         * may be started on the same key.
         *
         * 这里执行的工作是提交数据，并释放锁，最后通知DiskLruCache刷新相关数据
         * 写好数据，一定不要忘记commit操作对数据进行提交，我们要把dirtyFiles里面的内容移动到cleanFiles里才能够让别的editor访问到
         */
        @Throws(IOException::class)
        fun commit() {
            synchronized(this@DiskLruCache) {
                check(!done)
                if (entry.currentEditor == this) {
                    completeEdit(this, true)
                }
                done = true
            }
        }

        /**
         * Aborts this edit. This releases the edit lock so another edit may be started on the same
         * key.
         * 终止编辑，并释放锁
         */
        @Throws(IOException::class)
        fun abort() {
            synchronized(this@DiskLruCache) {
                check(!done)
                if (entry.currentEditor == this) {
                    completeEdit(this, false)
                }
                done = true
            }
        }
    }

    /** 存储的缓存数据的实体类，每一个url对应一个Entry实体 */
    internal inner class Entry internal constructor(
            // 构造器 就一个入参 key，而key又是url，所以，一个url对应一个Entry
            internal val key: String
    ) {

        /** Lengths of this entry's files. */
        internal val lengths: LongArray = LongArray(valueCount)//文件比特数
        internal val cleanFiles = mutableListOf<File>()
        internal val dirtyFiles = mutableListOf<File>()

        /** 可读性 True if this entry has ever been published. */
        /** 实体是否可读，可读为true，不可读为false*/
        internal var readable: Boolean = false

        /** 编辑器，如果实体没有被编辑过，则为null*/
        internal var currentEditor: Editor? = null

        /** The sequence number of the most recently committed edit to this entry. */
        /** 最近提交的Entry的序列号 */
        internal var sequenceNumber: Long = 0

        /*初始化代码块*/
        init {
            // The names are repetitive so re-use the same builder to avoid allocations.
            val fileBuilder = StringBuilder(key).append('.')
            val truncateTo = fileBuilder.length
            //由于valueCount为2,所以循环了2次，一共创建了4份文件
            //分别为key.1文件和key.1.tmp文件
            //           key.2文件和key.2.tmp文件
            for (i in 0 until valueCount) {
                fileBuilder.append(i)
                cleanFiles += File(directory, fileBuilder.toString())
                fileBuilder.append(".tmp")
                dirtyFiles += File(directory, fileBuilder.toString())
                fileBuilder.setLength(truncateTo)
            }
        }

        /** Set lengths using decimal numbers like "10123". */
        @Throws(IOException::class)
        internal fun setLengths(strings: List<String>) {
            if (strings.size != valueCount) {
                throw invalidLengths(strings)
            }

            try {
                for (i in strings.indices) {
                    lengths[i] = strings[i].toLong()
                }
            } catch (_: NumberFormatException) {
                throw invalidLengths(strings)
            }
        }

        /** Append space-prefixed lengths to [writer]. */
        @Throws(IOException::class)
        internal fun writeLengths(writer: BufferedSink) {
            for (length in lengths) {
                writer.writeByte(' '.toInt()).writeDecimalLong(length)
            }
        }

        @Throws(IOException::class)
        private fun invalidLengths(strings: List<String>): IOException {
            throw IOException("unexpected journal line: $strings")
        }

        /**
         * Returns a snapshot of this entry. This opens all streams eagerly to guarantee that we see a
         * single published snapshot. If we opened streams lazily then the streams could come from
         * different edits.
         */
        internal fun snapshot(): Snapshot? {
            //首先判断 线程是否有DiskLruCache对象的锁
            this@DiskLruCache.assertThreadHoldsLock()

            //new了一个Souce类型数组，容量为2
            val sources = mutableListOf<Source>()
            //clone一个long类型的数组，容量为2
            val lengths = this.lengths.clone() // Defensive copy since these can be zeroed out.
            //获取cleanFile的Source，用于读取cleanFile中的数据，并用得到的souce、Entry.key、Entry.length、sequenceNumber数据构造一个Snapshot对象
            try {
                for (i in 0 until valueCount) {
                    sources += fileSystem.source(cleanFiles[i])
                }
                return Snapshot(key, sequenceNumber, sources, lengths)
            } catch (_: FileNotFoundException) {
                // A file must have been deleted manually!
                for (source in sources) {
                    source.closeQuietly()
                }
                // Since the entry is no longer valid, remove it so the metadata is accurate (i.e. the cache
                // size.)
                try {
                    removeEntry(this)
                } catch (_: IOException) {
                }
                return null
            }
        }
    }

    companion object {
        /** 日志文件名 */
        @JvmField
        val JOURNAL_FILE = "journal"

        @JvmField
        val JOURNAL_FILE_TEMP = "journal.tmp"

        @JvmField
        val JOURNAL_FILE_BACKUP = "journal.bkp"

        @JvmField
        val MAGIC = "libcore.io.DiskLruCache"

        @JvmField
        val VERSION_1 = "1"

        /** 任何序列号 */
        @JvmField
        val ANY_SEQUENCE_NUMBER: Long = -1

        @JvmField
        val LEGAL_KEY_PATTERN = "[a-z0-9_-]{1,120}".toRegex()

        @JvmField
        val CLEAN = "CLEAN"

        @JvmField
        val DIRTY = "DIRTY"

        @JvmField
        val REMOVE = "REMOVE"

        @JvmField
        val READ = "READ"
    }
}
