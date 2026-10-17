import os, sys, sqlite3, json, shutil, csv, calendar, subprocess, platform
from datetime import date, datetime
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

APP_NAME = "Hotel Store Inventory"
def data_dir():
    if os.name == "nt":
        root = os.environ.get("LOCALAPPDATA", os.path.expanduser("~"))
    else:
        root = os.path.expanduser("~/.local/share")
    p = os.path.join(root, "HotelStoreInventory")
    os.makedirs(p, exist_ok=True)
    return p
DATA_DIR = data_dir()
DB_PATH = os.path.join(DATA_DIR, "hotel_store.db")

def dbconn():
    c=sqlite3.connect(DB_PATH)
    c.row_factory=sqlite3.Row
    c.execute("PRAGMA foreign_keys=ON")
    return c

def init_db():
    with dbconn() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS items(
          id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE,
          category TEXT NOT NULL DEFAULT 'Uncategorized', unit TEXT NOT NULL DEFAULT 'Piece',
          opening REAL NOT NULL DEFAULT 0, min_stock REAL NOT NULL DEFAULT 0,
          location TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
          active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS movements(
          id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL,
          movement TEXT NOT NULL CHECK(movement IN ('IN','OUT')), qty REAL NOT NULL CHECK(qty>0),
          movement_date TEXT NOT NULL, person TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
          created_at TEXT NOT NULL, FOREIGN KEY(item_id) REFERENCES items(id)
        );
        CREATE INDEX IF NOT EXISTS idx_mov_date ON movements(movement_date);
        CREATE INDEX IF NOT EXISTS idx_mov_item ON movements(item_id);
        """)

def current_balance(item_id, until=None):
    with dbconn() as c:
        item=c.execute("SELECT opening FROM items WHERE id=?",(item_id,)).fetchone()
        if not item: return 0
        q="SELECT COALESCE(SUM(CASE WHEN movement='IN' THEN qty ELSE -qty END),0) n FROM movements WHERE item_id=?"
        args=[item_id]
        if until:
            q += " AND movement_date<=?"; args.append(until)
        return float(item["opening"])+float(c.execute(q,args).fetchone()["n"])

def fmt(n):
    return f"{float(n):,.3f}".rstrip("0").rstrip(".") if "." in f"{float(n):.3f}" else f"{float(n):,.0f}"

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME); self.geometry("1180x740"); self.minsize(900,600)
        self.configure(bg="#f2f5f3")
        self.style=ttk.Style(self); self.style.theme_use("clam")
        self.style.configure("Treeview", rowheight=28, font=("Segoe UI",10), background="white", fieldbackground="white")
        self.style.configure("Treeview.Heading", font=("Segoe UI",10,"bold"), background="#e7eee9")
        self.style.configure("TButton", padding=7, font=("Segoe UI",10))
        self.style.configure("Primary.TButton", background="#174b3b", foreground="white")
        self.style.map("Primary.TButton", background=[("active","#23644f")])
        init_db()
        self.header()
        self.tabs=ttk.Notebook(self)
        self.tabs.pack(fill="both",expand=True,padx=14,pady=(0,12))
        self.build_dashboard(); self.build_items(); self.build_movements(); self.build_history(); self.build_reports(); self.build_backup()
        self.refresh_all()

    def header(self):
        h=tk.Frame(self,bg="#173b32",height=72); h.pack(fill="x"); h.pack_propagate(False)
        tk.Label(h,text="HOTEL STORE INVENTORY",font=("Segoe UI",19,"bold"),fg="white",bg="#173b32").pack(anchor="w",padx=20,pady=(10,0))
        tk.Label(h,text="Offline stock register  •  No prices  •  Local database",font=("Segoe UI",10),fg="#d4e6dd",bg="#173b32").pack(anchor="w",padx=21)

    def tab(self,title):
        f=ttk.Frame(self.tabs,padding=14); self.tabs.add(f,text=title); return f
    def heading(self,parent,title,sub=None):
        ttk.Label(parent,text=title,font=("Segoe UI",16,"bold")).pack(anchor="w",pady=(0,4))
        if sub: ttk.Label(parent,text=sub,foreground="#64746b").pack(anchor="w",pady=(0,12))
    def tree(self,parent,cols,headings,widths=None):
        wrap=ttk.Frame(parent); wrap.pack(fill="both",expand=True,pady=(8,0))
        tree=ttk.Treeview(wrap,columns=cols,show="headings")
        vs=ttk.Scrollbar(wrap,orient="vertical",command=tree.yview); hs=ttk.Scrollbar(wrap,orient="horizontal",command=tree.xview)
        tree.configure(yscrollcommand=vs.set,xscrollcommand=hs.set)
        tree.grid(row=0,column=0,sticky="nsew"); vs.grid(row=0,column=1,sticky="ns"); hs.grid(row=1,column=0,sticky="ew")
        wrap.rowconfigure(0,weight=1); wrap.columnconfigure(0,weight=1)
        for idx,col in enumerate(cols):
            tree.heading(col,text=headings[idx]); tree.column(col,width=(widths[idx] if widths else 120),anchor="w")
        return tree
    def build_dashboard(self):
        f=self.tab("Dashboard"); self.heading(f,"Store Dashboard","Quick overview of quantities and low-stock items")
        self.stats=tk.Frame(f,bg="#f2f5f3"); self.stats.pack(fill="x",pady=8)
        self.stat_labels=[]
        for title in ["Active items","Current stock total*","Received this month*","Issued this month*"]:
            card=tk.Frame(self.stats,bg="white",highlightbackground="#d9e2dc",highlightthickness=1,padx=16,pady=12)
            card.pack(side="left",fill="x",expand=True,padx=(0,10))
            tk.Label(card,text=title,bg="white",fg="#66766d",font=("Segoe UI",9)).pack(anchor="w")
            v=tk.Label(card,text="0",bg="white",fg="#173b32",font=("Segoe UI",22,"bold")); v.pack(anchor="w")
            self.stat_labels.append(v)
        ttk.Label(f,text="*Headline quantities add different units together. Use item-wise balances for accurate unit-specific counts.",foreground="#66766d").pack(anchor="w",pady=4)
        ttk.Label(f,text="Low / zero stock",font=("Segoe UI",12,"bold")).pack(anchor="w",pady=(14,0))
        self.low_tree=self.tree(f,("name","category","balance","unit","minimum"),("Item","Category","Balance","Unit","Alert level"),[260,180,120,100,120])
    def build_items(self):
        f=self.tab("Items"); self.heading(f,"Items Register","Add items, set units and opening stock. Prices are not recorded.")
        form=ttk.LabelFrame(f,text="Item details",padding=10); form.pack(fill="x")
        self.item_vars={k:tk.StringVar() for k in ["name","category","unit","opening","minimum","location","notes"]}
        self.item_vars["category"].set("Uncategorized"); self.item_vars["unit"].set("Piece"); self.item_vars["opening"].set("0"); self.item_vars["minimum"].set("0")
        fields=[("Item name *","name"),("Category","category"),("Unit","unit"),("Opening stock","opening"),("Low-stock alert","minimum"),("Storage location","location"),("Notes","notes")]
        self.item_widgets={}
        for idx,(label,key) in enumerate(fields):
            r=idx//4; col=(idx%4)*2
            ttk.Label(form,text=label).grid(row=r,column=col,sticky="w",padx=(0,6),pady=5)
            if key=="unit":
                w=ttk.Combobox(form,textvariable=self.item_vars[key],values=["Piece","Packet","Box","Kg","Gram","Litre","Millilitre","Bottle","Roll","Set","Bag","Carton","Pair","Metre","Other"],state="readonly",width=16)
            else: w=ttk.Entry(form,textvariable=self.item_vars[key],width=22)
            w.grid(row=r,column=col+1,sticky="ew",padx=(0,14),pady=5); self.item_widgets[key]=w
        for c in range(8): form.columnconfigure(c,weight=1)
        btns=ttk.Frame(form); btns.grid(row=2,column=0,columnspan=8,sticky="w",pady=(8,0))
        ttk.Button(btns,text="Save item",command=self.save_item,style="Primary.TButton").pack(side="left",padx=(0,8))
        ttk.Button(btns,text="Clear form",command=self.clear_item_form).pack(side="left",padx=(0,8))
        ttk.Button(btns,text="Edit selected",command=self.edit_item).pack(side="left",padx=(0,8))
        ttk.Button(btns,text="Archive selected",command=self.archive_item).pack(side="left")
        self.editing_item=None
        row=ttk.Frame(f); row.pack(fill="x",pady=(14,0)); ttk.Label(row,text="Search").pack(side="left")
        self.item_search=tk.StringVar(); ent=ttk.Entry(row,textvariable=self.item_search,width=32); ent.pack(side="left",padx=8); ent.bind("<KeyRelease>",lambda e:self.refresh_items())
        self.items_tree=self.tree(f,("id","name","category","unit","opening","received","issued","balance","minimum","location"),("ID","Item","Category","Unit","Opening","Received","Issued","Balance","Alert","Location"),[45,190,130,85,85,85,85,85,75,130])
    def clear_item_form(self):
        self.editing_item=None
        for k,v in self.item_vars.items(): v.set({"category":"Uncategorized","unit":"Piece","opening":"0","minimum":"0"}.get(k,""))
        self.item_widgets["opening"].configure(state="normal")
    def save_item(self):
        name=self.item_vars["name"].get().strip()
        if not name: messagebox.showerror("Required","Enter an item name."); return
        try:
            opening=float(self.item_vars["opening"].get() or 0); minimum=float(self.item_vars["minimum"].get() or 0)
            if opening<0 or minimum<0: raise ValueError()
        except ValueError: messagebox.showerror("Invalid quantity","Opening stock and alert level must be zero or greater."); return
        vals=(name,self.item_vars["category"].get().strip() or "Uncategorized",self.item_vars["unit"].get() or "Piece",opening,minimum,self.item_vars["location"].get().strip(),self.item_vars["notes"].get().strip())
        try:
            with dbconn() as c:
                if self.editing_item:
                    c.execute("UPDATE items SET name=?,category=?,unit=?,min_stock=?,location=?,notes=? WHERE id=?",(vals[0],vals[1],vals[2],vals[4],vals[5],vals[6],self.editing_item))
                else:
                    c.execute("INSERT INTO items(name,category,unit,opening,min_stock,location,notes,created_at) VALUES(?,?,?,?,?,?,?,?)",(*vals,date.today().isoformat()))
            self.clear_item_form(); self.refresh_all()
        except sqlite3.IntegrityError: messagebox.showerror("Duplicate item","An item with that name already exists.")
    def selected_item_id(self):
        sel=self.items_tree.selection()
        if not sel: messagebox.showinfo("Select item","Select an item first."); return None
        return int(self.items_tree.item(sel[0],"values")[0])
    def edit_item(self):
        iid=self.selected_item_id()
        if iid is None:return
        with dbconn() as c: i=c.execute("SELECT * FROM items WHERE id=?",(iid,)).fetchone()
        self.editing_item=iid
        for k in ["name","category","unit","minimum","location","notes"]:
            self.item_vars[k].set(str(i[{"minimum":"min_stock"}.get(k,k)]))
        self.item_vars["opening"].set(str(i["opening"])); self.item_widgets["opening"].configure(state="disabled")
    def archive_item(self):
        iid=self.selected_item_id()
        if iid is None:return
        if messagebox.askyesno("Archive item","Hide this item from active lists? Historical records remain saved."):
            with dbconn() as c:c.execute("UPDATE items SET active=0 WHERE id=?",(iid,))
            self.refresh_all()
    def build_movements(self):
        f=self.tab("Stock In / Out"); self.heading(f,"Record Stock Movement","Balance is calculated automatically. Stock cannot be issued below zero.")
        form=ttk.LabelFrame(f,text="Movement details",padding=14); form.pack(fill="x",anchor="n")
        self.mov_vars={k:tk.StringVar() for k in ["type","item","qty","date","person","notes"]}
        self.mov_vars["type"].set("Stock In (Received)"); self.mov_vars["date"].set(date.today().isoformat())
        labels=[("Movement","type"),("Item","item"),("Quantity","qty"),("Date (YYYY-MM-DD)","date"),("Issued to / Received from","person"),("Remarks / reference","notes")]
        for idx,(lab,key) in enumerate(labels):
            r=idx//2; c=(idx%2)*2
            ttk.Label(form,text=lab).grid(row=r,column=c,sticky="w",padx=(0,8),pady=8)
            if key=="type": w=ttk.Combobox(form,textvariable=self.mov_vars[key],values=["Stock In (Received)","Stock Out (Issued)"],state="readonly",width=28)
            elif key=="item": w=ttk.Combobox(form,textvariable=self.mov_vars[key],state="readonly",width=35)
            else:w=ttk.Entry(form,textvariable=self.mov_vars[key],width=38)
            w.grid(row=r,column=c+1,sticky="ew",padx=(0,18),pady=8)
            if key=="item": self.mov_item_widget=w; w.bind("<<ComboboxSelected>>",lambda e:self.update_balance_label())
        form.columnconfigure(1,weight=1); form.columnconfigure(3,weight=1)
        self.balance_label=ttk.Label(form,text="Select an item to view balance",foreground="#245b4b"); self.balance_label.grid(row=3,column=0,columnspan=4,sticky="w",pady=8)
        ttk.Button(form,text="Save movement",command=self.save_movement,style="Primary.TButton").grid(row=4,column=0,sticky="w",pady=(8,0))
        ttk.Button(form,text="Clear",command=self.clear_movement).grid(row=4,column=1,sticky="w",pady=(8,0))
    def clear_movement(self):
        for k,v in self.mov_vars.items(): v.set({"type":"Stock In (Received)","date":date.today().isoformat()}.get(k,""))
        self.update_balance_label()
    def chosen_movement_item(self):
        s=self.mov_vars["item"].get()
        if not s:return None
        try:return int(s.split(" | ")[0])
        except:return None
    def update_balance_label(self):
        iid=self.chosen_movement_item()
        if iid:
            with dbconn() as c:i=c.execute("SELECT unit FROM items WHERE id=?",(iid,)).fetchone()
            if i:self.balance_label.config(text=f"Current balance: {fmt(current_balance(iid))} {i['unit']}")
        else:self.balance_label.config(text="Select an item to view balance")
    def save_movement(self):
        iid=self.chosen_movement_item()
        try:
            qty=float(self.mov_vars["qty"].get()); datetime.strptime(self.mov_vars["date"].get(),"%Y-%m-%d")
            if not iid or qty<=0:raise ValueError()
        except ValueError:messagebox.showerror("Invalid entry","Select an item and enter a positive quantity and valid date (YYYY-MM-DD).");return
        typ="IN" if self.mov_vars["type"].get().startswith("Stock In") else "OUT"
        if typ=="OUT" and qty>current_balance(iid):
            messagebox.showerror("Insufficient stock",f"Current balance is {fmt(current_balance(iid))}. Issue quantity cannot exceed available stock.");return
        with dbconn() as c:c.execute("INSERT INTO movements(item_id,movement,qty,movement_date,person,notes,created_at) VALUES(?,?,?,?,?,?,?)",(iid,typ,qty,self.mov_vars["date"].get(),self.mov_vars["person"].get().strip(),self.mov_vars["notes"].get().strip(),datetime.now().isoformat(timespec="seconds")))
        messagebox.showinfo("Saved","Stock movement saved and balance updated.");self.clear_movement();self.refresh_all()
    def build_history(self):
        f=self.tab("History"); self.heading(f,"Transaction History","Filter by date, item or movement. Deleting a record recalculates stock.")
        bar=ttk.Frame(f);bar.pack(fill="x")
        self.hist_from=tk.StringVar();self.hist_to=tk.StringVar();self.hist_item=tk.StringVar();self.hist_type=tk.StringVar(value="All")
        for label,var,width in [("From (YYYY-MM-DD)",self.hist_from,13),("To (YYYY-MM-DD)",self.hist_to,13)]:
            ttk.Label(bar,text=label).pack(side="left");ttk.Entry(bar,textvariable=var,width=width).pack(side="left",padx=(5,12))
        ttk.Label(bar,text="Item").pack(side="left");self.hist_item_widget=ttk.Combobox(bar,textvariable=self.hist_item,state="readonly",width=24);self.hist_item_widget.pack(side="left",padx=5)
        ttk.Label(bar,text="Type").pack(side="left");ttk.Combobox(bar,textvariable=self.hist_type,values=["All","Stock In","Stock Out"],state="readonly",width=12).pack(side="left",padx=5)
        ttk.Button(bar,text="Apply",command=self.refresh_history).pack(side="left",padx=4);ttk.Button(bar,text="Delete selected",command=self.delete_movement).pack(side="left",padx=4)
        self.history_tree=self.tree(f,("id","date","item","type","qty","unit","person","notes"),("ID","Date","Item","Movement","Quantity","Unit","Issued to / From","Remarks"),[45,100,190,100,95,80,180,220])
    def refresh_history(self):
        for x in self.history_tree.get_children():self.history_tree.delete(x)
        clauses=[];args=[]
        for val,op in [(self.hist_from.get().strip(),">="),(self.hist_to.get().strip(),"<=")]:
            if val:
                try:datetime.strptime(val,"%Y-%m-%d")
                except ValueError:messagebox.showerror("Date format","Use YYYY-MM-DD for date filters.");return
                clauses.append("m.movement_date "+op+" ?");args.append(val)
        item=self.hist_item.get()
        if item and item!="All items":clauses.append("m.item_id=?");args.append(int(item.split(" | ")[0]))
        typ=self.hist_type.get()
        if typ!="All":clauses.append("m.movement=?");args.append("IN" if typ=="Stock In" else "OUT")
        sql="SELECT m.*,i.name,i.unit FROM movements m JOIN items i ON i.id=m.item_id"
        if clauses:sql+=" WHERE "+" AND ".join(clauses)
        sql+=" ORDER BY m.movement_date DESC,m.id DESC"
        with dbconn() as c:rows=c.execute(sql,args).fetchall()
        for r in rows:self.history_tree.insert("","end",values=(r["id"],r["movement_date"],r["name"],"Stock In" if r["movement"]=="IN" else "Stock Out",fmt(r["qty"]),r["unit"],r["person"],r["notes"]))
    def delete_movement(self):
        sel=self.history_tree.selection()
        if not sel:messagebox.showinfo("Select record","Select a transaction first.");return
        mid=int(self.history_tree.item(sel[0],"values")[0])
        if messagebox.askyesno("Delete transaction","Delete this movement? Stock balance and reports will be recalculated."):
            with dbconn() as c:c.execute("DELETE FROM movements WHERE id=?",(mid,))
            self.refresh_all()
    def build_reports(self):
        f=self.tab("Monthly Reports");self.heading(f,"Monthly Stock Report","Opening balance + received − issued = closing balance. Export to Excel or PDF.")
        bar=ttk.Frame(f);bar.pack(fill="x",pady=(0,8))
        self.report_month=tk.StringVar(value=date.today().strftime("%Y-%m"));self.report_cat=tk.StringVar(value="All categories")
        ttk.Label(bar,text="Month (YYYY-MM)").pack(side="left");ttk.Entry(bar,textvariable=self.report_month,width=12).pack(side="left",padx=6)
        ttk.Label(bar,text="Category").pack(side="left");self.report_cat_widget=ttk.Combobox(bar,textvariable=self.report_cat,state="readonly",width=22);self.report_cat_widget.pack(side="left",padx=6)
        ttk.Button(bar,text="Generate",command=self.refresh_report,style="Primary.TButton").pack(side="left",padx=4)
        ttk.Button(bar,text="Export Excel (.xlsx)",command=self.export_excel).pack(side="left",padx=4)
        ttk.Button(bar,text="Export PDF",command=self.export_pdf).pack(side="left",padx=4)
        self.report_summary=ttk.Label(f,text="Choose a month and generate report.",font=("Segoe UI",10,"bold"));self.report_summary.pack(anchor="w",pady=8)
        self.report_tree=self.tree(f,("name","category","unit","opening","received","issued","closing"),("Item","Category","Unit","Opening balance","Received","Issued","Closing balance"),[210,140,85,120,100,100,120])
    def report_rows(self):
        month=self.report_month.get().strip()
        try:
            y,m=map(int,month.split("-")); start=date(y,m,1).isoformat(); end=date(y,m,calendar.monthrange(y,m)[1]).isoformat()
        except Exception:messagebox.showerror("Month format","Enter month as YYYY-MM, for example 2026-10.");return None
        cat=self.report_cat.get()
        with dbconn() as c:
            sql="SELECT * FROM items WHERE active=1";args=[]
            if cat and cat!="All categories":sql+=" AND category=?";args.append(cat)
            sql+=" ORDER BY name COLLATE NOCASE";items=c.execute(sql,args).fetchall()
            rows=[]
            for i in items:
                before=c.execute("SELECT COALESCE(SUM(CASE WHEN movement='IN' THEN qty ELSE -qty END),0) n FROM movements WHERE item_id=? AND movement_date<?",(i["id"],start)).fetchone()["n"]
                opening=float(i["opening"])+float(before)
                ins=c.execute("SELECT COALESCE(SUM(qty),0) n FROM movements WHERE item_id=? AND movement='IN' AND movement_date BETWEEN ? AND ?",(i["id"],start,end)).fetchone()["n"]
                outs=c.execute("SELECT COALESCE(SUM(qty),0) n FROM movements WHERE item_id=? AND movement='OUT' AND movement_date BETWEEN ? AND ?",(i["id"],start,end)).fetchone()["n"]
                rows.append({"name":i["name"],"category":i["category"],"unit":i["unit"],"opening":opening,"received":float(ins),"issued":float(outs),"closing":opening+float(ins)-float(outs)})
        return month,rows
    def refresh_report(self):
        result=self.report_rows()
        if result is None:return
        month,rows=result
        for x in self.report_tree.get_children():self.report_tree.delete(x)
        for r in rows:self.report_tree.insert("","end",values=(r["name"],r["category"],r["unit"],fmt(r["opening"]),fmt(r["received"]),fmt(r["issued"]),fmt(r["closing"])))
        self.report_summary.config(text=f"{month}  |  Items: {len(rows)}  |  Received total*: {fmt(sum(r['received'] for r in rows))}  |  Issued total*: {fmt(sum(r['issued'] for r in rows))}")
    def export_excel(self):
        result=self.report_rows()
        if result is None:return
        month,rows=result
        path=filedialog.asksaveasfilename(defaultextension=".xlsx",initialfile=f"Hotel_Store_Report_{month}.xlsx",filetypes=[("Excel workbook","*.xlsx")])
        if not path:return
        try:
            from openpyxl import Workbook
            from openpyxl.styles import Font, PatternFill, Alignment
            wb=Workbook();ws=wb.active;ws.title="Monthly Stock Report"
            ws.append([f"Hotel Store Monthly Stock Report — {month}"]);ws.append(["Item","Category","Unit","Opening Balance","Received","Issued","Closing Balance"])
            for r in rows:ws.append([r["name"],r["category"],r["unit"],r["opening"],r["received"],r["issued"],r["closing"]])
            for cell in ws[1]:cell.font=Font(bold=True,size=14)
            for cell in ws[2]:
                cell.font=Font(bold=True,color="FFFFFF");cell.fill=PatternFill("solid",fgColor="173B32")
            ws.freeze_panes="A3";ws.auto_filter.ref=ws.dimensions
            for col in ws.columns:
                letter=col[0].column_letter;ws.column_dimensions[letter].width=min(32,max(12,max(len(str(c.value or "")) for c in col)+2))
            wb.save(path);messagebox.showinfo("Export complete","Excel report saved:\n"+path)
        except Exception as e:messagebox.showerror("Export failed",str(e))
    def export_pdf(self):
        result=self.report_rows()
        if result is None:return
        month,rows=result
        path=filedialog.asksaveasfilename(defaultextension=".pdf",initialfile=f"Hotel_Store_Report_{month}.pdf",filetypes=[("PDF document","*.pdf")])
        if not path:return
        try:
            from reportlab.lib import colors
            from reportlab.lib.pagesizes import A4, landscape
            from reportlab.platypus import SimpleDocTemplate,Table,TableStyle,Paragraph,Spacer
            from reportlab.lib.styles import getSampleStyleSheet
            from reportlab.lib.units import mm
            doc=SimpleDocTemplate(path,pagesize=landscape(A4),rightMargin=12*mm,leftMargin=12*mm,topMargin=12*mm,bottomMargin=12*mm)
            styles=getSampleStyleSheet();story=[Paragraph(f"Hotel Store Monthly Stock Report — {month}",styles["Title"]),Paragraph("Quantity only · No prices included",styles["Normal"]),Spacer(1,8)]
            data=[["Item","Category","Unit","Opening Balance","Received","Issued","Closing Balance"]]
            for r in rows:data.append([r["name"],r["category"],r["unit"],fmt(r["opening"]),fmt(r["received"]),fmt(r["issued"]),fmt(r["closing"])])
            table=Table(data,repeatRows=1,colWidths=[65*mm,40*mm,22*mm,34*mm,28*mm,28*mm,34*mm])
            table.setStyle(TableStyle([("BACKGROUND",(0,0),(-1,0),colors.HexColor("#173B32")),("TEXTCOLOR",(0,0),(-1,0),colors.white),("FONTNAME",(0,0),(-1,0),"Helvetica-Bold"),("GRID",(0,0),(-1,-1),.35,colors.HexColor("#CBD6CF")),("ROWBACKGROUNDS",(0,1),(-1,-1),[colors.white,colors.HexColor("#F2F5F3")]),("FONTSIZE",(0,0),(-1,-1),8),("PADDING",(0,0),(-1,-1),5)]))
            story.append(table);doc.build(story);messagebox.showinfo("Export complete","PDF report saved:\n"+path)
        except Exception as e:messagebox.showerror("Export failed",str(e))
    def build_backup(self):
        f=self.tab("Backup / Restore");self.heading(f,"Backup & Restore","Back up all items and movement history. Keep a copy on a separate drive.")
        ttk.Button(f,text="Export backup (JSON)",command=self.backup,style="Primary.TButton").pack(anchor="w",pady=6)
        ttk.Separator(f).pack(fill="x",pady=14)
        ttk.Label(f,text="Restore replaces the current database on this computer.",foreground="#a52d2d").pack(anchor="w",pady=(0,8))
        ttk.Button(f,text="Restore from backup (JSON)",command=self.restore).pack(anchor="w",pady=4)
        ttk.Label(f,text="Offline data is stored locally on this computer. Phone and computer do not sync automatically; transfer data with backup/restore.",wraplength=850,foreground="#64746b").pack(anchor="w",pady=16)
    def backup(self):
        path=filedialog.asksaveasfilename(defaultextension=".json",initialfile="Hotel_Store_Backup_"+date.today().isoformat()+".json",filetypes=[("JSON backup","*.json")])
        if not path:return
        with dbconn() as c:
            payload={"app":"Hotel Store Inventory","version":1,"exported_at":datetime.now().isoformat(),"items":[dict(r) for r in c.execute("SELECT * FROM items")],"movements":[dict(r) for r in c.execute("SELECT * FROM movements")]}
        with open(path,"w",encoding="utf-8") as fp:json.dump(payload,fp,ensure_ascii=False,indent=2)
        messagebox.showinfo("Backup saved","Backup saved successfully.")
    def restore(self):
        path=filedialog.askopenfilename(filetypes=[("JSON backup","*.json")])
        if not path:return
        if not messagebox.askyesno("Confirm restore","This replaces current data on this computer. Make a backup first. Continue?"):return
        try:
            with open(path,encoding="utf-8") as fp:d=json.load(fp)
            if not isinstance(d.get("items"),list) or not isinstance(d.get("movements"),list):raise ValueError("This is not a valid Hotel Store backup.")
            with dbconn() as c:
                c.execute("PRAGMA foreign_keys=OFF")
                c.execute("DELETE FROM movements");c.execute("DELETE FROM items")
                for i in d["items"]:
                    c.execute("INSERT INTO items(id,name,category,unit,opening,min_stock,location,notes,active,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",(i["id"],i["name"],i.get("category","Uncategorized"),i.get("unit","Piece"),i.get("opening",0),i.get("min_stock",0),i.get("location",""),i.get("notes",""),i.get("active",1),i.get("created_at",date.today().isoformat())))
                for m in d["movements"]:
                    c.execute("INSERT INTO movements(id,item_id,movement,qty,movement_date,person,notes,created_at) VALUES(?,?,?,?,?,?,?,?)",(m["id"],m["item_id"],m["movement"],m["qty"],m["movement_date"],m.get("person",""),m.get("notes",""),m.get("created_at","")))
                c.execute("PRAGMA foreign_keys=ON")
            self.refresh_all();messagebox.showinfo("Restore complete","Backup restored.")
        except Exception as e:messagebox.showerror("Restore failed",str(e))
    def refresh_items(self):
        for x in self.items_tree.get_children():self.items_tree.delete(x)
        q=self.item_search.get().strip().lower()
        with dbconn() as c:items=c.execute("SELECT * FROM items WHERE active=1 ORDER BY name COLLATE NOCASE").fetchall()
        for i in items:
            if q and q not in (i["name"]+" "+i["category"]+" "+i["location"]).lower():continue
            with dbconn() as c:
                ins=c.execute("SELECT COALESCE(SUM(qty),0) n FROM movements WHERE item_id=? AND movement='IN'",(i["id"],)).fetchone()["n"]
                outs=c.execute("SELECT COALESCE(SUM(qty),0) n FROM movements WHERE item_id=? AND movement='OUT'",(i["id"],)).fetchone()["n"]
            self.items_tree.insert("","end",values=(i["id"],i["name"],i["category"],i["unit"],fmt(i["opening"]),fmt(ins),fmt(outs),fmt(current_balance(i["id"])),fmt(i["min_stock"]),i["location"]))
    def refresh_dashboard(self):
        m=date.today().strftime("%Y-%m");start=m+"-01";end=m+"-"+str(calendar.monthrange(date.today().year,date.today().month)[1])
        with dbconn() as c:
            items=c.execute("SELECT * FROM items WHERE active=1").fetchall()
            ins=c.execute("SELECT COALESCE(SUM(qty),0) n FROM movements WHERE movement='IN' AND movement_date BETWEEN ? AND ?",(start,end)).fetchone()["n"]
            outs=c.execute("SELECT COALESCE(SUM(qty),0) n FROM movements WHERE movement='OUT' AND movement_date BETWEEN ? AND ?",(start,end)).fetchone()["n"]
        self.stat_labels[0].config(text=str(len(items)));self.stat_labels[1].config(text=fmt(sum(current_balance(i["id"]) for i in items)));self.stat_labels[2].config(text=fmt(ins));self.stat_labels[3].config(text=fmt(outs))
        for x in self.low_tree.get_children():self.low_tree.delete(x)
        for i in items:
            b=current_balance(i["id"])
            if b<=float(i["min_stock"]):self.low_tree.insert("","end",values=(i["name"],i["category"],fmt(b),i["unit"],fmt(i["min_stock"])))
    def refresh_picklists(self):
        with dbconn() as c:items=c.execute("SELECT id,name,unit,category FROM items WHERE active=1 ORDER BY name COLLATE NOCASE").fetchall()
        vals=[f'{i["id"]} | {i["name"]} ({i["unit"]})' for i in items]
        self.mov_item_widget["values"]=vals;self.hist_item_widget["values"]=["All items"]+vals
        cats=sorted(set(i["category"] for i in items))
        self.report_cat_widget["values"]=["All categories"]+cats
        if self.hist_item.get() not in self.hist_item_widget["values"]:self.hist_item.set("All items")
        if self.report_cat.get() not in self.report_cat_widget["values"]:self.report_cat.set("All categories")
    def refresh_all(self):
        self.refresh_picklists();self.refresh_items();self.refresh_dashboard();self.refresh_history();self.refresh_report()

if __name__=="__main__":
    App().mainloop()
