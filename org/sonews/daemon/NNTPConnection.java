/*
 *   SONEWS News Server
 *   see AUTHORS for the list of contributors
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sonews.daemon;

import org.sonews.util.Log;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import org.sonews.daemon.command.Command;
import org.sonews.storage.Article;
import org.sonews.storage.Channel;
import org.sonews.util.Stats;

/**
 * For every SocketChannel (so TCP/IP connection) there is an instance of
 * this class.
 * @author Christian Lins
 * @since sonews/0.5.0
 */
public final class NNTPConnection
{

  public static final String NEWLINE            = "\r\n";    // RFC defines this as newline
  public static final String MESSAGE_ID_PATTERN = "<[^>]+>";
  
  private static final Timer cancelTimer = new Timer(true); // Thread-safe? True for run as daemon
  
  /** SocketChannel is generally thread-safe */
  private SocketChannel   channel        = null;
  private Charset         charset        = Charset.forName("UTF-8");
  private Command         command        = null;
  private Article         currentArticle = null;
  private Channel         currentGroup   = null;
  private volatile long   lastActivity   = System.currentTimeMillis();
  private ChannelLineBuffers lineBuffers = new ChannelLineBuffers();
  private int             readLock       = 0;
  private final Object    readLockGate   = new Object();
  private SelectionKey    writeSelKey    = null;
  
  public NNTPConnection(final SocketChannel channel)
    throws IOException
  {
    if(channel == null)
    {
      throw new IllegalArgumentException("channel is null");
    }

    this.channel = channel;
    Stats.getInstance().clientConnect();
  }
  
  /**
   * Tries to get the read lock for this NNTPConnection. This method is Thread-
   * safe and returns true of the read lock was successfully set. If the lock
   * is still hold by another Thread the method returns false.
   */
  boolean tryReadLock()
  {
    // As synchronizing simple types may cause deadlocks,
    // we use a gate object.
    synchronized(readLockGate)
    {
      if(readLock != 0)
      {
        return false;
      }
      else
      {
        readLock = Thread.currentThread().hashCode();
        return true;
      }
    }
  }
  
  /**
   * Releases the read lock in a Thread-safe way.
   * @throws IllegalMonitorStateException if a Thread not holding the lock
   * tries to release it.
   */
  void unlockReadLock()
  {
    synchronized(readLockGate)
    {
      if(readLock == Thread.currentThread().hashCode())
      {
        readLock = 0;
      }
      else
      {
        throw new IllegalMonitorStateException();
      }
    }
  }
  
  /**
   * @return Current input buffer of this NNTPConnection instance.
   */
  public ByteBuffer getInputBuffer()
  {
    return this.lineBuffers.getInputBuffer();
  }
  
  /**
   * @return Output buffer of this NNTPConnection which has at least one byte
   * free storage.
   */
  public ByteBuffer getOutputBuffer()
  {
    return this.lineBuffers.getOutputBuffer();
  }
  
  /**
   * @return ChannelLineBuffers instance associated with this NNTPConnection.
   */
  public ChannelLineBuffers getBuffers()
  {
    return this.lineBuffers;
  }
  
  /**
   * @return true if this connection comes from a local remote address.
   */
  public boolean isLocalConnection()
  {
    return ((InetSocketAddress)this.channel.socket().getRemoteSocketAddress())
      .getHostName().equalsIgnoreCase("localhost");
  }

  void setWriteSelectionKey(SelectionKey selKey)
  {
    this.writeSelKey = selKey;
  }

  public void shutdownInput()
  {
    try
    {
      // Closes the input line of the channel's socket, so no new data
      // will be received and a timeout can be triggered.
      this.channel.socket().shutdownInput();
    }
    catch(IOException ex)
    {
      Log.msg("Exception in NNTPConnection.shutdownInput(): " + ex, false);
      if(Log.isDebug())
      {
        ex.printStackTrace();
      }
    }
  }
  
  public void shutdownOutput()
  {
    cancelTimer.schedule(new TimerTask() 
    {
      @Override
      public void run()
      {
        try
        {
          // Closes the output line of the channel's socket.
          channel.socket().shutdownOutput();
          channel.close();
        }
        catch(SocketException ex)
        {
          // Socket was already disconnected
          Log.msg("NNTPConnection.shutdownOutput(): " + ex, true);
        }
        catch(Exception ex)
        {
          Log.msg("NNTPConnection.shutdownOutput(): " + ex, false);
          if(Log.isDebug())
          {
            ex.printStackTrace();
          }
        }
      }
    }, 3000);
  }
  
  public SocketChannel getSocketChannel()
  {
    return this.channel;
  }
  
  public Article getCurrentArticle()
  {
    return this.currentArticle;
  }
  
  public Charset getCurrentCharset()
  {
    return this.charset;
  }

  /**
   * @return The currently selected communication channel (not SocketChannel)
   */
  public Channel getCurrentChannel()
  {
    return this.currentGroup;
  }
  
  public void setCurrentArticle(final Article article)
  {
    this.currentArticle = article;
  }
  
  public void setCurrentGroup(final Channel group)
  {
    this.currentGroup = group;
  }
  
  public long getLastActivity()
  {
    return this.lastActivity;
  }
  
  /**
   * Due to the readLockGate there is no need to synchronize this method.
   * @param raw
   * @throws IllegalArgumentException if raw is null.
   * @throws IllegalStateException if calling thread does not own the readLock.
   */
  void lineReceived(byte[] raw)
  {
    if(raw == null)
    {
      throw new IllegalArgumentException("raw is null");
    }
    
    if(readLock == 0 || readLock != Thread.currentThread().hashCode())
    {
      throw new IllegalStateException("readLock not properly set");
    }

    this.lastActivity = System.currentTimeMillis();
    
    String line = new String(raw, this.charset);
    
    // There might be a trailing \r, but trim() is a bad idea
    // as it removes also leading spaces from long header lines.
    if(line.endsWith("\r"))
    {
      line = line.substring(0, line.length() - 1);
      raw  = Arrays.copyOf(raw, raw.length - 1);
    }
    
    Log.msg("<< " + line, true);
    
    if(command == null)
    {
      command = parseCommandLine(line);
      assert command != null;
    }

    try
    {
      // The command object will process the line we just received
      command.processLine(this, line, raw);
    }
    catch(ClosedChannelException ex0)
    {
      try
      {
        Log.msg("Connection to " + channel.socket().getRemoteSocketAddress() 
            + " closed: " + ex0, true);
      }
      catch(Exception ex0a)
      {
        ex0a.printStackTrace();
      }
    }
    catch(Exception ex1)
    {
      try
      {
        command = null;
        ex1.printStackTrace();
        println("500 Internal server error");
      }
      catch(Exception ex2)
      {
        ex2.printStackTrace();
      }
    }

    if(command == null || command.hasFinished())
    {
      command = null;
      charset = Charset.forName("UTF-8"); // Reset to default
    }
  }
  
  /**
   * This method determines the fitting command processing class.
   * @param line
   * @return
   */
  private Command parseCommandLine(String line)
  {
    String cmdStr = line.split(" ")[0];
    return CommandSelector.getInstance().get(cmdStr);
  }
  
  /**
   * Puts the given line into the output buffer, adds a newline character
   * and returns. The method returns immediately and does not block until
   * the line was sent. If line is longer than 510 octets it is split up in
   * several lines. Each line is terminated by \r\n (NNTPConnection.NEWLINE).
   * @param line
   */
  public void println(final CharSequence line, final Charset charset)
    throws IOException
  {    
    writeToChannel(CharBuffer.wrap(line), charset, line);
    writeToChannel(CharBuffer.wrap(NEWLINE), charset, null);
  }

  /**
   * Writes the given raw lines to the output buffers and finishes with
   * a newline character (\r\n).
   * @param rawLines
   */
  public void println(final byte[] rawLines)
    throws IOException
  {
    this.lineBuffers.addOutputBuffer(ByteBuffer.wrap(rawLines));
    writeToChannel(CharBuffer.wrap(NEWLINE), charset, null);
  }
  
  /**
   * Encodes the given CharBuffer using the given Charset to a bunch of
   * ByteBuffers (each 512 bytes large) and enqueues them for writing at the
   * connected SocketChannel.
   * @throws java.io.IOException
   */
  private void writeToChannel(CharBuffer characters, final Charset charset,
    CharSequence debugLine)
    throws IOException
  {
    if(!charset.canEncode())
    {
      Log.msg("FATAL: Charset " + charset + " cannot encode!", false);
      return;
    }
    
    // Write characters to output buffers
    LineEncoder lenc = new LineEncoder(characters, charset);
    lenc.encode(lineBuffers);
    
    enableWriteEvents(debugLine);
  }

  private void enableWriteEvents(CharSequence debugLine)
  {
    // Enable OP_WRITE events so that the buffers are processed
    try
    {
      this.writeSelKey.interestOps(SelectionKey.OP_WRITE);
      ChannelWriter.getInstance().getSelector().wakeup();
    }
    catch (Exception ex) // CancelledKeyException and ChannelCloseException
    {
      Log.msg("NNTPConnection.writeToChannel(): " + ex, false);
      return;
    }

    // Update last activity timestamp
    this.lastActivity = System.currentTimeMillis();
    if(debugLine != null)
    {
      Log.msg(">> " + debugLine, true);
    }
  }
  
  public void println(final CharSequence line)
    throws IOException
  {
    println(line, charset);
  }
  
  public void print(final String line)
    throws IOException
  {
    writeToChannel(CharBuffer.wrap(line), charset, line);
  }
  
  public void setCurrentCharset(final Charset charset)
  {
    this.charset = charset;
  }
  
}
