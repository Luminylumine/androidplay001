using System.ComponentModel;

namespace AdbManager;

public partial class ConnectTcpForm : Form
{
    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public string IpAddress { get; private set; } = string.Empty;
    
    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public int Port { get; private set; } = 5555;
    
    [Browsable(false)]
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public string? PairingCode { get; private set; }

    private System.Windows.Forms.TextBox txtIpAddress;
    private System.Windows.Forms.NumericUpDown numPort;
    private System.Windows.Forms.Label label1;
    private System.Windows.Forms.Label label2;
    private System.Windows.Forms.Button btnConnect;
    private System.Windows.Forms.Button btnCancel;
    private System.Windows.Forms.CheckBox chkUsePairing;
    private System.Windows.Forms.Label label3;
    private System.Windows.Forms.TextBox txtPairingCode;

    public ConnectTcpForm()
    {
        InitializeComponent();
    }

    private void InitializeComponent()
    {
        txtIpAddress = new System.Windows.Forms.TextBox();
        numPort = new System.Windows.Forms.NumericUpDown();
        label1 = new System.Windows.Forms.Label();
        label2 = new System.Windows.Forms.Label();
        btnConnect = new System.Windows.Forms.Button();
        btnCancel = new System.Windows.Forms.Button();
        chkUsePairing = new System.Windows.Forms.CheckBox();
        label3 = new System.Windows.Forms.Label();
        txtPairingCode = new System.Windows.Forms.TextBox();
        ((System.ComponentModel.ISupportInitialize)(numPort)).BeginInit();
        SuspendLayout();

        // txtIpAddress
        txtIpAddress.Location = new System.Drawing.Point(12, 30);
        txtIpAddress.Name = "txtIpAddress";
        txtIpAddress.Size = new System.Drawing.Size(150, 23);
        txtIpAddress.TabIndex = 0;

        // numPort
        numPort.Location = new System.Drawing.Point(170, 30);
        numPort.Maximum = new decimal(new int[] { 65535, 0, 0, 0 });
        numPort.Minimum = new decimal(new int[] { 1, 0, 0, 0 });
        numPort.Name = "numPort";
        numPort.Size = new System.Drawing.Size(70, 23);
        numPort.TabIndex = 1;
        numPort.Value = new decimal(new int[] { 37379, 0, 0, 0 });

        // label1
        label1.AutoSize = true;
        label1.Location = new System.Drawing.Point(12, 12);
        label1.Name = "label1";
        label1.Size = new System.Drawing.Size(86, 15);
        label1.TabIndex = 2;
        label1.Text = "IP 地址：";

        // label2
        label2.AutoSize = true;
        label2.Location = new System.Drawing.Point(170, 12);
        label2.Name = "label2";
        label2.Size = new System.Drawing.Size(38, 15);
        label2.TabIndex = 3;
        label2.Text = "端口：";

        // btnConnect
        btnConnect.DialogResult = System.Windows.Forms.DialogResult.OK;
        btnConnect.Location = new System.Drawing.Point(86, 130);
        btnConnect.Name = "btnConnect";
        btnConnect.Size = new System.Drawing.Size(80, 30);
        btnConnect.TabIndex = 4;
        btnConnect.Text = "连接";
        btnConnect.UseVisualStyleBackColor = true;
        btnConnect.Click += btnConnect_Click;

        // btnCancel
        btnCancel.DialogResult = System.Windows.Forms.DialogResult.Cancel;
        btnCancel.Location = new System.Drawing.Point(180, 130);
        btnCancel.Name = "btnCancel";
        btnCancel.Size = new System.Drawing.Size(80, 30);
        btnCancel.TabIndex = 5;
        btnCancel.Text = "取消";
        btnCancel.UseVisualStyleBackColor = true;

        // chkUsePairing
        chkUsePairing.AutoSize = true;
        chkUsePairing.Location = new System.Drawing.Point(12, 70);
        chkUsePairing.Name = "chkUsePairing";
        chkUsePairing.Size = new System.Drawing.Size(138, 19);
        chkUsePairing.TabIndex = 6;
        chkUsePairing.Text = "需要配对码（首次连接）";
        chkUsePairing.UseVisualStyleBackColor = true;
        chkUsePairing.CheckedChanged += chkUsePairing_CheckedChanged;

        // label3
        label3.AutoSize = true;
        label3.Enabled = false;
        label3.Location = new System.Drawing.Point(12, 95);
        label3.Name = "label3";
        label3.Size = new System.Drawing.Size(54, 15);
        label3.TabIndex = 7;
        label3.Text = "配对码：";

        // txtPairingCode
        txtPairingCode.Enabled = false;
        txtPairingCode.Location = new System.Drawing.Point(72, 92);
        txtPairingCode.Name = "txtPairingCode";
        txtPairingCode.Size = new System.Drawing.Size(100, 23);
        txtPairingCode.TabIndex = 8;

        // ConnectTcpForm
        AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
        AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
        ClientSize = new System.Drawing.Size(272, 172);
        Controls.Add(txtPairingCode);
        Controls.Add(label3);
        Controls.Add(chkUsePairing);
        Controls.Add(btnCancel);
        Controls.Add(btnConnect);
        Controls.Add(label2);
        Controls.Add(numPort);
        Controls.Add(label1);
        Controls.Add(txtIpAddress);
        FormBorderStyle = System.Windows.Forms.FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        Name = "ConnectTcpForm";
        StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
        Text = "连接 TCP 设备";
        ((System.ComponentModel.ISupportInitialize)(numPort)).EndInit();
        ResumeLayout(false);
        PerformLayout();
    }

    private void chkUsePairing_CheckedChanged(object? sender, EventArgs e)
    {
        var enabled = chkUsePairing.Checked;
        txtPairingCode.Enabled = enabled;
        label3.Enabled = enabled;
    }

    private void btnConnect_Click(object? sender, EventArgs e)
    {
        if (string.IsNullOrWhiteSpace(txtIpAddress.Text))
        {
            MessageBox.Show("请输入有效的 IP 地址", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            DialogResult = DialogResult.None;
            return;
        }

        IpAddress = txtIpAddress.Text.Trim();
        Port = (int)numPort.Value;

        if (chkUsePairing.Checked)
        {
            if (string.IsNullOrWhiteSpace(txtPairingCode.Text))
            {
                MessageBox.Show("请输入配对码", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                DialogResult = DialogResult.None;
                return;
            }
            PairingCode = txtPairingCode.Text.Trim();
        }
    }
}
