using System.ComponentModel;

namespace AdbManager;

public partial class ScrcpySettingsForm : Form
{
    private readonly string _deviceId;

    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public ScreenMode ScreenMode { get; private set; } = ScreenMode.StayAwake;

    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public KeyboardMode KeyboardMode { get; private set; } = KeyboardMode.Sdk;

    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public bool NoAudio { get; private set; } = true;

    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public int? MaxFps { get; private set; } = 60;

    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public int? BitRate { get; private set; } = 8;

    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public string? MaxSize { get; private set; } = "1024";

    private System.Windows.Forms.GroupBox grpScreen;
    private System.Windows.Forms.RadioButton radioStayAwake;
    private System.Windows.Forms.RadioButton radioClickToWake;
    private System.Windows.Forms.GroupBox grpKeyboard;
    private System.Windows.Forms.RadioButton radioKbUhid;
    private System.Windows.Forms.RadioButton radioKbSdk;
    private System.Windows.Forms.RadioButton radioKbOff;
    private System.Windows.Forms.Button btnKbSettings;
    private System.Windows.Forms.CheckBox chkNoAudio;
    private System.Windows.Forms.Label label1;
    private System.Windows.Forms.NumericUpDown numFps;
    private System.Windows.Forms.Label label2;
    private System.Windows.Forms.NumericUpDown numBitRate;
    private System.Windows.Forms.Label label3;
    private System.Windows.Forms.ComboBox cmbMaxSize;
    private System.Windows.Forms.Button btnStart;
    private System.Windows.Forms.Button btnCancel;
    private System.Windows.Forms.Label label4;
    private System.Windows.Forms.Label label5;

    public ScrcpySettingsForm(string deviceId)
    {
        _deviceId = deviceId;
        InitializeComponent();
    }

    private void InitializeComponent()
    {
        grpScreen = new System.Windows.Forms.GroupBox();
        radioStayAwake = new System.Windows.Forms.RadioButton();
        radioClickToWake = new System.Windows.Forms.RadioButton();
        grpKeyboard = new System.Windows.Forms.GroupBox();
        radioKbUhid = new System.Windows.Forms.RadioButton();
        radioKbSdk = new System.Windows.Forms.RadioButton();
        radioKbOff = new System.Windows.Forms.RadioButton();
        btnKbSettings = new System.Windows.Forms.Button();
        chkNoAudio = new System.Windows.Forms.CheckBox();
        label1 = new System.Windows.Forms.Label();
        numFps = new System.Windows.Forms.NumericUpDown();
        label2 = new System.Windows.Forms.Label();
        numBitRate = new System.Windows.Forms.NumericUpDown();
        label3 = new System.Windows.Forms.Label();
        cmbMaxSize = new System.Windows.Forms.ComboBox();
        btnStart = new System.Windows.Forms.Button();
        btnCancel = new System.Windows.Forms.Button();
        label4 = new System.Windows.Forms.Label();
        label5 = new System.Windows.Forms.Label();
        ((System.ComponentModel.ISupportInitialize)(numFps)).BeginInit();
        ((System.ComponentModel.ISupportInitialize)(numBitRate)).BeginInit();
        SuspendLayout();

        // grpScreen
        grpScreen.Controls.Add(radioStayAwake);
        grpScreen.Controls.Add(radioClickToWake);
        grpScreen.Location = new System.Drawing.Point(12, 12);
        grpScreen.Name = "grpScreen";
        grpScreen.Size = new System.Drawing.Size(336, 68);
        grpScreen.TabIndex = 0;
        grpScreen.TabStop = false;
        grpScreen.Text = "屏幕行为";

        // radioStayAwake
        radioStayAwake.AutoSize = true;
        radioStayAwake.Checked = true;
        radioStayAwake.Location = new System.Drawing.Point(12, 22);
        radioStayAwake.Name = "radioStayAwake";
        radioStayAwake.Size = new System.Drawing.Size(150, 19);
        radioStayAwake.TabIndex = 0;
        radioStayAwake.TabStop = true;
        radioStayAwake.Text = "不黑屏（屏幕常亮，推荐）";
        radioStayAwake.UseVisualStyleBackColor = true;

        // radioClickToWake
        radioClickToWake.AutoSize = true;
        radioClickToWake.Location = new System.Drawing.Point(12, 44);
        radioClickToWake.Name = "radioClickToWake";
        radioClickToWake.Size = new System.Drawing.Size(210, 19);
        radioClickToWake.TabIndex = 1;
        radioClickToWake.Text = "黑屏但可点亮（点击投屏窗口唤醒）";
        radioClickToWake.UseVisualStyleBackColor = true;

        // grpKeyboard
        grpKeyboard.Controls.Add(radioKbUhid);
        grpKeyboard.Controls.Add(radioKbSdk);
        grpKeyboard.Controls.Add(radioKbOff);
        grpKeyboard.Controls.Add(btnKbSettings);
        grpKeyboard.Location = new System.Drawing.Point(12, 86);
        grpKeyboard.Name = "grpKeyboard";
        grpKeyboard.Size = new System.Drawing.Size(336, 92);
        grpKeyboard.TabIndex = 1;
        grpKeyboard.TabStop = false;
        grpKeyboard.Text = "键盘输入（电脑键盘 → 手机）";

        // radioKbUhid
        radioKbUhid.AutoSize = true;
        radioKbUhid.Location = new System.Drawing.Point(12, 22);
        radioKbUhid.Name = "radioKbUhid";
        radioKbUhid.Size = new System.Drawing.Size(200, 19);
        radioKbUhid.TabIndex = 0;
        radioKbUhid.TabStop = true;
        radioKbUhid.Text = "物理键盘（UHID，支持完整键盘，需手机支持）";
        radioKbUhid.UseVisualStyleBackColor = true;

        // radioKbSdk
        radioKbSdk.AutoSize = true;
        radioKbSdk.Checked = true;
        radioKbSdk.Location = new System.Drawing.Point(12, 44);
        radioKbSdk.Name = "radioKbSdk";
        radioKbSdk.Size = new System.Drawing.Size(180, 19);
        radioKbSdk.TabIndex = 1;
        radioKbSdk.Text = "SDK 兼容模式（英文/有限字符，非中文）";
        radioKbSdk.UseVisualStyleBackColor = true;

        // radioKbOff
        radioKbOff.AutoSize = true;
        radioKbOff.Location = new System.Drawing.Point(12, 66);
        radioKbOff.Name = "radioKbOff";
        radioKbOff.Size = new System.Drawing.Size(80, 19);
        radioKbOff.TabIndex = 2;
        radioKbOff.Text = "禁用";
        radioKbOff.UseVisualStyleBackColor = true;

        // btnKbSettings
        btnKbSettings.Location = new System.Drawing.Point(230, 40);
        btnKbSettings.Name = "btnKbSettings";
        btnKbSettings.Size = new System.Drawing.Size(96, 28);
        btnKbSettings.TabIndex = 3;
        btnKbSettings.Text = "手机键盘设置";
        btnKbSettings.UseVisualStyleBackColor = true;
        btnKbSettings.Click += btnKbSettings_Click;

        // chkNoAudio
        chkNoAudio.AutoSize = true;
        chkNoAudio.Checked = true;
        chkNoAudio.CheckState = System.Windows.Forms.CheckState.Checked;
        chkNoAudio.Location = new System.Drawing.Point(12, 186);
        chkNoAudio.Name = "chkNoAudio";
        chkNoAudio.Size = new System.Drawing.Size(138, 19);
        chkNoAudio.TabIndex = 2;
        chkNoAudio.Text = "不传输音频（省带宽）";
        chkNoAudio.UseVisualStyleBackColor = true;

        // label1
        label1.AutoSize = true;
        label1.Location = new System.Drawing.Point(12, 216);
        label1.Name = "label1";
        label1.Size = new System.Drawing.Size(54, 15);
        label1.TabIndex = 3;
        label1.Text = "最大帧率：";

        // numFps
        numFps.Location = new System.Drawing.Point(72, 213);
        numFps.Maximum = new decimal(new int[] { 240, 0, 0, 0 });
        numFps.Minimum = new decimal(new int[] { 5, 0, 0, 0 });
        numFps.Name = "numFps";
        numFps.Size = new System.Drawing.Size(60, 23);
        numFps.TabIndex = 4;
        numFps.Value = new decimal(new int[] { 60, 0, 0, 0 });

        // label2
        label2.AutoSize = true;
        label2.Location = new System.Drawing.Point(145, 216);
        label2.Name = "label2";
        label2.Size = new System.Drawing.Size(54, 15);
        label2.TabIndex = 5;
        label2.Text = "码率(M)：";

        // numBitRate
        numBitRate.Location = new System.Drawing.Point(205, 213);
        numBitRate.Maximum = new decimal(new int[] { 100, 0, 0, 0 });
        numBitRate.Minimum = new decimal(new int[] { 1, 0, 0, 0 });
        numBitRate.Name = "numBitRate";
        numBitRate.Size = new System.Drawing.Size(60, 23);
        numBitRate.TabIndex = 6;
        numBitRate.Value = new decimal(new int[] { 8, 0, 0, 0 });

        // label3
        label3.AutoSize = true;
        label3.Location = new System.Drawing.Point(12, 246);
        label3.Name = "label3";
        label3.Size = new System.Drawing.Size(74, 15);
        label3.TabIndex = 7;
        label3.Text = "最大分辨率：";

        // cmbMaxSize
        cmbMaxSize.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
        cmbMaxSize.FormattingEnabled = true;
        cmbMaxSize.Items.AddRange(new object[] { "720", "1024", "1280", "1920", "原始" });
        cmbMaxSize.Location = new System.Drawing.Point(92, 243);
        cmbMaxSize.Name = "cmbMaxSize";
        cmbMaxSize.Size = new System.Drawing.Size(100, 23);
        cmbMaxSize.TabIndex = 8;
        cmbMaxSize.SelectedIndex = 1;

        // label4
        label4.AutoSize = true;
        label4.ForeColor = System.Drawing.SystemColors.GrayText;
        label4.Location = new System.Drawing.Point(12, 276);
        label4.Name = "label4";
        label4.Size = new System.Drawing.Size(300, 15);
        label4.TabIndex = 9;
        label4.Text = "中文输入：启动后在主页“PC输入法”框输入并发送到手机";

        // label5
        label5.AutoSize = true;
        label5.ForeColor = System.Drawing.SystemColors.GrayText;
        label5.Location = new System.Drawing.Point(12, 292);
        label5.Name = "label5";
        label5.Size = new System.Drawing.Size(300, 15);
        label5.TabIndex = 10;
        label5.Text = "黑屏模式下：手机熄灭后，点击投屏窗口即可点亮";

        // btnStart
        btnStart.DialogResult = System.Windows.Forms.DialogResult.OK;
        btnStart.Location = new System.Drawing.Point(100, 320);
        btnStart.Name = "btnStart";
        btnStart.Size = new System.Drawing.Size(80, 30);
        btnStart.TabIndex = 11;
        btnStart.Text = "开始共享";
        btnStart.UseVisualStyleBackColor = true;
        btnStart.Click += btnStart_Click;

        // btnCancel
        btnCancel.DialogResult = System.Windows.Forms.DialogResult.Cancel;
        btnCancel.Location = new System.Drawing.Point(200, 320);
        btnCancel.Name = "btnCancel";
        btnCancel.Size = new System.Drawing.Size(80, 30);
        btnCancel.TabIndex = 12;
        btnCancel.Text = "取消";
        btnCancel.UseVisualStyleBackColor = true;

        // ScrcpySettingsForm
        AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
        AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
        ClientSize = new System.Drawing.Size(360, 362);
        Controls.Add(label5);
        Controls.Add(label4);
        Controls.Add(btnCancel);
        Controls.Add(btnStart);
        Controls.Add(cmbMaxSize);
        Controls.Add(label3);
        Controls.Add(numBitRate);
        Controls.Add(label2);
        Controls.Add(numFps);
        Controls.Add(label1);
        Controls.Add(chkNoAudio);
        Controls.Add(grpKeyboard);
        Controls.Add(grpScreen);
        FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        Name = "ScrcpySettingsForm";
        StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
        Text = "屏幕共享设置";
        ((System.ComponentModel.ISupportInitialize)(numFps)).EndInit();
        ((System.ComponentModel.ISupportInitialize)(numBitRate)).EndInit();
        ResumeLayout(false);
        PerformLayout();
    }

    private async void btnKbSettings_Click(object? sender, EventArgs e)
    {
        btnKbSettings.Enabled = false;
        try
        {
            await AdbHelper.OpenPhysicalKeyboardSettingsAsync(_deviceId);
        }
        catch (Exception ex)
        {
            MessageBox.Show($"无法打开键盘设置: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            btnKbSettings.Enabled = true;
        }
    }

    private void btnStart_Click(object? sender, EventArgs e)
    {
        ScreenMode = radioClickToWake.Checked ? ScreenMode.ClickToWake : ScreenMode.StayAwake;

        if (radioKbUhid.Checked)
            KeyboardMode = KeyboardMode.Uhid;
        else if (radioKbOff.Checked)
            KeyboardMode = KeyboardMode.Disabled;
        else
            KeyboardMode = KeyboardMode.Sdk; // 默认走 SDK 兼容模式，兼容性最好

        NoAudio = chkNoAudio.Checked;
        MaxFps = (int)numFps.Value;
        BitRate = (int)numBitRate.Value;

        var size = cmbMaxSize.SelectedItem?.ToString();
        MaxSize = size == "原始" ? null : size;
    }
}
