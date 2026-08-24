namespace AdbManager;

partial class MainForm
{
    private System.ComponentModel.IContainer components = null;
    private System.Windows.Forms.ListView listViewDevices;
    private System.Windows.Forms.ColumnHeader colName;
    private System.Windows.Forms.ColumnHeader colType;
    private System.Windows.Forms.Button btnRefresh;
    private System.Windows.Forms.StatusStrip statusStrip;
    private System.Windows.Forms.ToolStripStatusLabel statusLabel;
    private System.Windows.Forms.ContextMenuStrip contextMenuStrip;
    private System.Windows.Forms.ToolStripMenuItem 传输文件ToolStripMenuItem;
    private System.Windows.Forms.ToolStripMenuItem 屏幕共享ToolStripMenuItem;
    private System.Windows.Forms.ToolStripMenuItem 访问相册ToolStripMenuItem;
    private System.Windows.Forms.ToolStripMenuItem 扩展屏ToolStripMenuItem;
    private System.Windows.Forms.ToolStripSeparator toolStripSeparator1;
    private System.Windows.Forms.ToolStripMenuItem 连接TCP设备ToolStripMenuItem;
    private System.Windows.Forms.Label label1;
    private System.Windows.Forms.Label pcInputLabel;
    private System.Windows.Forms.TextBox pcInputBox;
    private System.Windows.Forms.ComboBox cmbPcTarget;
    private System.Windows.Forms.Button pcSendBtn;

    protected override void Dispose(bool disposing)
    {
        if (disposing && (components != null))
        {
            components.Dispose();
        }
        base.Dispose(disposing);
    }

    private void InitializeComponent()
    {
        components = new System.ComponentModel.Container();
        listViewDevices = new System.Windows.Forms.ListView();
        colName = new System.Windows.Forms.ColumnHeader();
        colType = new System.Windows.Forms.ColumnHeader();
        btnRefresh = new System.Windows.Forms.Button();
        statusStrip = new System.Windows.Forms.StatusStrip();
        statusLabel = new System.Windows.Forms.ToolStripStatusLabel();
        contextMenuStrip = new System.Windows.Forms.ContextMenuStrip(components);
        传输文件ToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
        屏幕共享ToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
        访问相册ToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
        扩展屏ToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
        toolStripSeparator1 = new System.Windows.Forms.ToolStripSeparator();
        连接TCP设备ToolStripMenuItem = new System.Windows.Forms.ToolStripMenuItem();
        label1 = new System.Windows.Forms.Label();
        pcInputLabel = new System.Windows.Forms.Label();
        pcInputBox = new System.Windows.Forms.TextBox();
        cmbPcTarget = new System.Windows.Forms.ComboBox();
        pcSendBtn = new System.Windows.Forms.Button();
        statusStrip.SuspendLayout();
        contextMenuStrip.SuspendLayout();
        SuspendLayout();

        // 
        // listViewDevices
        // 
        listViewDevices.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] { colName, colType });
        listViewDevices.Dock = System.Windows.Forms.DockStyle.None;
        listViewDevices.FullRowSelect = true;
        listViewDevices.GridLines = true;
        listViewDevices.HideSelection = false;
        listViewDevices.Location = new System.Drawing.Point(0, 78);
        listViewDevices.MultiSelect = false;
        listViewDevices.Name = "listViewDevices";
        listViewDevices.Size = new System.Drawing.Size(684, 325);
        listViewDevices.TabIndex = 0;
        listViewDevices.UseCompatibleStateImageBehavior = false;
        listViewDevices.View = System.Windows.Forms.View.Details;
        listViewDevices.SelectedIndexChanged += listViewDevices_SelectedIndexChanged;
        listViewDevices.MouseDown += listViewDevices_MouseDown;
        // 
        // colName
        // 
        colName.Text = "设备名称";
        colName.Width = 450;
        // 
        // colType
        // 
        colType.Text = "连接方式";
        colType.Width = 120;
        // 
        // btnRefresh
        // 
        btnRefresh.Location = new System.Drawing.Point(12, 12);
        btnRefresh.Name = "btnRefresh";
        btnRefresh.Size = new System.Drawing.Size(100, 28);
        btnRefresh.TabIndex = 1;
        btnRefresh.Text = "刷新设备";
        btnRefresh.UseVisualStyleBackColor = true;
        btnRefresh.Click += btnRefresh_Click;
        // 
        // statusStrip
        // 
        statusStrip.Items.AddRange(new System.Windows.Forms.ToolStripItem[] { statusLabel });
        statusStrip.Location = new System.Drawing.Point(0, 403);
        statusStrip.Name = "statusStrip";
        statusStrip.Size = new System.Drawing.Size(684, 22);
        statusStrip.TabIndex = 2;
        // 
        // statusLabel
        // 
        statusLabel.Name = "statusLabel";
        statusLabel.Size = new System.Drawing.Size(32, 17);
        statusLabel.Text = "就绪";
        // 
        // contextMenuStrip
        // 
        contextMenuStrip.Items.AddRange(new System.Windows.Forms.ToolStripItem[] { 传输文件ToolStripMenuItem, 屏幕共享ToolStripMenuItem, 访问相册ToolStripMenuItem, 扩展屏ToolStripMenuItem, toolStripSeparator1, 连接TCP设备ToolStripMenuItem });
        contextMenuStrip.Name = "contextMenuStrip";
        contextMenuStrip.Size = new System.Drawing.Size(153, 148);
        // 
        // 传输文件ToolStripMenuItem
        // 
        传输文件ToolStripMenuItem.Name = "传输文件ToolStripMenuItem";
        传输文件ToolStripMenuItem.Size = new System.Drawing.Size(152, 22);
        传输文件ToolStripMenuItem.Text = "文件传输";
        传输文件ToolStripMenuItem.Click += 传输文件ToolStripMenuItem_Click;
        // 
        // 屏幕共享ToolStripMenuItem
        // 
        屏幕共享ToolStripMenuItem.Name = "屏幕共享ToolStripMenuItem";
        屏幕共享ToolStripMenuItem.Size = new System.Drawing.Size(152, 22);
        屏幕共享ToolStripMenuItem.Text = "屏幕共享";
        屏幕共享ToolStripMenuItem.Click += 屏幕共享ToolStripMenuItem_Click;
        // 
        // 访问相册ToolStripMenuItem
        // 
        访问相册ToolStripMenuItem.Name = "访问相册ToolStripMenuItem";
        访问相册ToolStripMenuItem.Size = new System.Drawing.Size(152, 22);
        访问相册ToolStripMenuItem.Text = "访问相册";
        访问相册ToolStripMenuItem.Click += 访问相册ToolStripMenuItem_Click;
        // 
        // 扩展屏ToolStripMenuItem
        // 
        扩展屏ToolStripMenuItem.Name = "扩展屏ToolStripMenuItem";
        扩展屏ToolStripMenuItem.Size = new System.Drawing.Size(152, 22);
        扩展屏ToolStripMenuItem.Text = "扩展屏";
        扩展屏ToolStripMenuItem.Click += 扩展屏ToolStripMenuItem_Click;
        // 
        // toolStripSeparator1
        // 
        toolStripSeparator1.Name = "toolStripSeparator1";
        toolStripSeparator1.Size = new System.Drawing.Size(149, 6);
        // 
        // 连接TCP设备ToolStripMenuItem
        // 
        连接TCP设备ToolStripMenuItem.Name = "连接TCP设备ToolStripMenuItem";
        连接TCP设备ToolStripMenuItem.Size = new System.Drawing.Size(152, 22);
        连接TCP设备ToolStripMenuItem.Text = "连接TCP设备...";
        连接TCP设备ToolStripMenuItem.Click += 连接TCP设备ToolStripMenuItem_Click;
        // 
        // label1
        // 
        label1.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Right)));
        label1.AutoSize = true;
        label1.Location = new System.Drawing.Point(580, 18);
        label1.Name = "label1";
        label1.Size = new System.Drawing.Size(88, 15);
        label1.TabIndex = 3;
        label1.Text = "右键点击设备操作";
        // 
        // pcInputLabel
        // 
        pcInputLabel.AutoSize = true;
        pcInputLabel.Location = new System.Drawing.Point(12, 53);
        pcInputLabel.Name = "pcInputLabel";
        pcInputLabel.Size = new System.Drawing.Size(70, 15);
        pcInputLabel.TabIndex = 4;
        pcInputLabel.Text = "PC输入法:";
        // 
        // pcInputBox
        // 
        pcInputBox.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Left)));
        pcInputBox.Enabled = false;
        pcInputBox.Location = new System.Drawing.Point(88, 49);
        pcInputBox.Name = "pcInputBox";
        pcInputBox.PlaceholderText = "用中文输入法输入，回车或点按钮发送";
        pcInputBox.Size = new System.Drawing.Size(240, 23);
        pcInputBox.TabIndex = 5;
        pcInputBox.KeyDown += pcInputBox_KeyDown;
        // 
        // cmbPcTarget
        // 
        cmbPcTarget.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Left)));
        cmbPcTarget.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
        cmbPcTarget.Enabled = false;
        cmbPcTarget.FormattingEnabled = true;
        cmbPcTarget.Location = new System.Drawing.Point(334, 47);
        cmbPcTarget.Name = "cmbPcTarget";
        cmbPcTarget.Size = new System.Drawing.Size(190, 23);
        cmbPcTarget.TabIndex = 6;
        // 
        // pcSendBtn
        // 
        pcSendBtn.Anchor = ((System.Windows.Forms.AnchorStyles)((System.Windows.Forms.AnchorStyles.Top | System.Windows.Forms.AnchorStyles.Right)));
        pcSendBtn.Enabled = false;
        pcSendBtn.Location = new System.Drawing.Point(530, 47);
        pcSendBtn.Name = "pcSendBtn";
        pcSendBtn.Size = new System.Drawing.Size(142, 27);
        pcSendBtn.TabIndex = 7;
        pcSendBtn.Text = "发送到手机";
        pcSendBtn.UseVisualStyleBackColor = true;
        pcSendBtn.Click += pcSendBtn_Click;
        // 
        // MainForm
        // 
        AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
        AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
        ClientSize = new System.Drawing.Size(684, 425);
        Controls.Add(pcSendBtn);
        Controls.Add(cmbPcTarget);
        Controls.Add(pcInputBox);
        Controls.Add(pcInputLabel);
        Controls.Add(label1);
        Controls.Add(btnRefresh);
        Controls.Add(listViewDevices);
        Controls.Add(statusStrip);
        MainMenuStrip = null;
        Name = "MainForm";
        StartPosition = System.Windows.Forms.FormStartPosition.CenterScreen;
        Text = "AdbManager - 安卓设备管理";
        statusStrip.ResumeLayout(false);
        statusStrip.PerformLayout();
        contextMenuStrip.ResumeLayout(false);
        ResumeLayout(false);
        PerformLayout();
    }
}
