using System;
using System.IO;
using System.Drawing;
using System.Windows.Forms;
public class AutomationProbe : Form {
 static string log=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"fixture-events.jsonl");
 static void Event(string name){File.AppendAllText(log,"{\"utc\":\""+DateTime.UtcNow.ToString("o")+"\",\"event\":\""+name+"\"}\n");}
 int clicks=0;
 public AutomationProbe(){
  Text="CCB Automation Diagnostic - SYNTHETIC ONLY";Size=new Size(620,520);StartPosition=FormStartPosition.CenterScreen;
  var panel=new FlowLayoutPanel{Dock=DockStyle.Fill,AutoScroll=true,FlowDirection=FlowDirection.TopDown,WrapContents=false};Controls.Add(panel);
  panel.Controls.Add(new Label{Text="Synthetic automation probe. No CCB data, credentials or network.",Width=550,Height=45});
  var input=new TextBox{Width=380,AccessibleName="Synthetic input"};panel.Controls.Add(input);
  var count=new Label{Text="Count: 0",Width=380,Height=35,AccessibleName="Counter"};panel.Controls.Add(count);
  var increment=new Button{Text="Increment synthetic counter",Width=380,Height=50,AccessibleName="Increment synthetic counter"};increment.Click+=(s,e)=>{clicks++;count.Text="Count: "+clicks;Event("increment-"+clicks);};panel.Controls.Add(increment);
  panel.Controls.Add(new Label{Text="Scroll below to find the bottom action.",Width=400,Height=600});
  var bottom=new Button{Text="Bottom action",Width=380,Height=50,AccessibleName="Bottom action"};bottom.Click+=(s,e)=>Event("bottom-click");panel.Controls.Add(bottom);
  Shown+=(s,e)=>Event("shown");ResizeEnd+=(s,e)=>Event("resize-completed");FormClosed+=(s,e)=>Event("normal-closed");
  var timeout=new Timer{Interval=240000};timeout.Tick+=(s,e)=>{Event("synthetic-fixture-auto-close-240s");timeout.Stop();Close();};timeout.Start();
 }
 [STAThread] public static void Main(){Event("started");Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new AutomationProbe());Event("message-loop-exited");}
}
