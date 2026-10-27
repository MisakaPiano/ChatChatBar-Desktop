using System;
using System.IO;
using System.Collections.Concurrent;
public sealed class ProbeObservation : IDisposable {
 public readonly ConcurrentQueue<string> Events=new ConcurrentQueue<string>();
 public readonly ConcurrentQueue<string> Errors=new ConcurrentQueue<string>();
 private readonly FileSystemWatcher watcher;
 public ProbeObservation(string path){
  watcher=new FileSystemWatcher(path,".portable-write-probe-*"); watcher.IncludeSubdirectories=true; watcher.InternalBufferSize=65536;
  watcher.Created+=(s,e)=>Events.Enqueue(DateTimeOffset.UtcNow.ToString("o")+" CREATED "+e.Name);
  watcher.Deleted+=(s,e)=>Events.Enqueue(DateTimeOffset.UtcNow.ToString("o")+" DELETED "+e.Name);
  watcher.Error+=(s,e)=>Errors.Enqueue(e.GetException().GetType().Name);
  watcher.EnableRaisingEvents=true;
 }
 public void Dispose(){watcher.Dispose();}
}

public sealed class ProcessTraceCapture : IDisposable {
 private readonly System.Diagnostics.Process process;
 private readonly StreamWriter output, error;
 private readonly object outputLock=new object(), errorLock=new object();
 private volatile bool disposed;
 public ProcessTraceCapture(System.Diagnostics.Process p,string outFile,string errFile){
  process=p;
  output=new StreamWriter(new FileStream(outFile,FileMode.CreateNew,FileAccess.Write,FileShare.ReadWrite),new System.Text.UTF8Encoding(false)){AutoFlush=true};
  error=new StreamWriter(new FileStream(errFile,FileMode.CreateNew,FileAccess.Write,FileShare.ReadWrite),new System.Text.UTF8Encoding(false)){AutoFlush=true};
  p.OutputDataReceived+=OnOutput;p.ErrorDataReceived+=OnError;
  p.BeginOutputReadLine();p.BeginErrorReadLine();
 }
 private void OnOutput(object sender,System.Diagnostics.DataReceivedEventArgs e){if(e.Data!=null)lock(outputLock){if(!disposed)output.WriteLine(e.Data);}}
 private void OnError(object sender,System.Diagnostics.DataReceivedEventArgs e){if(e.Data!=null)lock(errorLock){if(!disposed)error.WriteLine(e.Data);}}
 public void Dispose(){disposed=true;process.OutputDataReceived-=OnOutput;process.ErrorDataReceived-=OnError;lock(outputLock){output.Dispose();}lock(errorLock){error.Dispose();}}
}