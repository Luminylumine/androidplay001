namespace AdbManager;

public partial class FileTransferForm : Form
{
    private readonly DeviceInfo _device;
    private string _currentPath = "/sdcard";

    private System.Windows.Forms.TreeView treeViewLocal;
    private System.Windows.Forms.ListView listViewRemote;
    private System.Windows.Forms.ColumnHeader colName;
    private System.Windows.Forms.ColumnHeader colSize;
    private System.Windows.Forms.ColumnHeader colDate;
    private System.Windows.Forms.TextBox txtRemotePath;
    private System.Windows.Forms.Button btnGoUp;
    private System.Windows.Forms.Button btnRefresh;
    private System.Windows.Forms.Button btnUpload;
    private System.Windows.Forms.Button btnDownload;
    private System.Windows.Forms.Button btnDelete;
    private System.Windows.Forms.Label label1;
    private System.Windows.Forms.StatusStrip statusStrip;
    private System.Windows.Forms.ToolStripStatusLabel statusLabel;
    private System.Windows.Forms.SaveFileDialog saveFileDialog;
    private System.Windows.Forms.OpenFileDialog openFileDialog;

    public FileTransferForm(DeviceInfo device)
    {
        _device = device;
        InitializeComponent();
        Load += FileTransferForm_Load;
    }

    private async void FileTransferForm_Load(object? sender, EventArgs e)
    {
        Text = $"文件传输 - {_device.DisplayName}";
        await LoadRemoteDirectoryAsync(_currentPath);
    }

    private async Task LoadRemoteDirectoryAsync(string path)
    {
        btnRefresh.Enabled = false;
        btnGoUp.Enabled = path != "/";
        txtRemotePath.Text = path;
        statusLabel.Text = $"加载 {path}...";

        try
        {
            var output = await AdbHelper.ListDirectoryAsync(_device.Id, path);
            ParseAndDisplayFiles(output);
            _currentPath = path;
            statusLabel.Text = $"显示 {listViewRemote.Items.Count} 个项目";
        }
        catch (Exception ex)
        {
            statusLabel.Text = $"加载失败: {ex.Message}";
            MessageBox.Show($"加载目录失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            btnRefresh.Enabled = true;
        }
    }

    private void ParseAndDisplayFiles(string output)
    {
        listViewRemote.Items.Clear();

        foreach (var line in output.Split('\n').Select(l => l.Trim()).Where(l => !string.IsNullOrEmpty(l)))
        {
            if (line.StartsWith("total")) continue;

            var isDir = line.StartsWith("d");
            var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);

            if (parts.Length >= 7)
            {
                var permissions = parts[0];
                var size = parts[4];
                var name = string.Join(" ", parts.Skip(7).TakeWhile(p => !p.Contains(':')));
                // Handle spaces in filenames - take everything from index 6+
                name = string.Join(" ", parts.Skip(6));

                var item = new ListViewItem(name)
                {
                    Tag = new FileEntry
                    {
                        Name = name,
                        Path = _currentPath == "/" ? $"/{name}" : $"{_currentPath}/{name}",
                        IsDirectory = permissions.StartsWith("d"),
                        Size = size
                    }
                };

                item.SubItems.Add(isDir ? "<DIR>" : size);
                
                // Get date - combine parts
                string date = $"{parts[5]} {parts[6]}";
                item.SubItems.Add(date);

                listViewRemote.Items.Add(item);
            }
        }
    }

    private async void listViewRemote_DoubleClick(object? sender, EventArgs e)
    {
        if (listViewRemote.SelectedItems.Count > 0)
        {
            var entry = (FileEntry?)listViewRemote.SelectedItems[0].Tag;
            if (entry != null && entry.IsDirectory)
            {
                await LoadRemoteDirectoryAsync(entry.Path);
            }
        }
    }

    private async void btnGoUp_Click(object? sender, EventArgs e)
    {
        if (_currentPath == "/") return;

        var parent = _currentPath.Substring(0, _currentPath.LastIndexOf('/'));
        if (string.IsNullOrEmpty(parent)) parent = "/";
        await LoadRemoteDirectoryAsync(parent);
    }

    private async void btnRefresh_Click(object? sender, EventArgs e)
    {
        await LoadRemoteDirectoryAsync(_currentPath);
    }

    private async void btnDownload_Click(object? sender, EventArgs e)
    {
        if (listViewRemote.SelectedItems.Count == 0)
        {
            MessageBox.Show("请先选择要下载的文件", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var entry = (FileEntry?)listViewRemote.SelectedItems[0].Tag;
        if (entry == null || entry.IsDirectory)
        {
            MessageBox.Show("请选择文件而非目录", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        saveFileDialog.FileName = entry.Name;
        if (saveFileDialog.ShowDialog(this) == DialogResult.OK)
        {
            statusLabel.Text = $"正在下载 {entry.Name}...";
            try
            {
                await AdbHelper.PullFileAsync(_device.Id, entry.Path, saveFileDialog.FileName);
                statusLabel.Text = $"下载完成：{saveFileDialog.FileName}";
                MessageBox.Show($"下载成功！\n保存位置：{saveFileDialog.FileName}", "完成", MessageBoxButtons.OK, MessageBoxIcon.Information);
            }
            catch (Exception ex)
            {
                statusLabel.Text = "下载失败";
                MessageBox.Show($"下载失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }

    private async void btnUpload_Click(object? sender, EventArgs e)
    {
        if (openFileDialog.ShowDialog(this) == DialogResult.OK)
        {
            var localFile = openFileDialog.FileName;
            var fileName = Path.GetFileName(localFile);
            var remotePath = _currentPath == "/" ? $"/{fileName}" : $"{_currentPath}/{fileName}";

            statusLabel.Text = $"正在上传 {fileName}...";
            try
            {
                await AdbHelper.PushFileAsync(_device.Id, localFile, remotePath);
                statusLabel.Text = $"上传完成：{fileName}";
                await LoadRemoteDirectoryAsync(_currentPath);
            }
            catch (Exception ex)
            {
                statusLabel.Text = "上传失败";
                MessageBox.Show($"上传失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }

    private async void btnDelete_Click(object? sender, EventArgs e)
    {
        if (listViewRemote.SelectedItems.Count == 0)
        {
            MessageBox.Show("请先选择要删除的文件", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var entry = (FileEntry?)listViewRemote.SelectedItems[0].Tag;
        if (entry == null) return;

        var result = MessageBox.Show(
            $"确定要删除 '{entry.Name}' 吗？\n此操作不可撤销。",
            "确认删除",
            MessageBoxButtons.YesNo,
            MessageBoxIcon.Warning);

        if (result == DialogResult.Yes)
        {
            try
            {
                string cmd = entry.IsDirectory ? $"rm -rf {entry.Path}" : $"rm {entry.Path}";
                await AdbHelper.ShellExecAsync(_device.Id, cmd);
                await LoadRemoteDirectoryAsync(_currentPath);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"删除失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }
    }

    private void InitializeComponent()
    {
        treeViewLocal = new System.Windows.Forms.TreeView();
        listViewRemote = new System.Windows.Forms.ListView();
        colName = new System.Windows.Forms.ColumnHeader();
        colSize = new System.Windows.Forms.ColumnHeader();
        colDate = new System.Windows.Forms.ColumnHeader();
        txtRemotePath = new System.Windows.Forms.TextBox();
        btnGoUp = new System.Windows.Forms.Button();
        btnRefresh = new System.Windows.Forms.Button();
        btnUpload = new System.Windows.Forms.Button();
        btnDownload = new System.Windows.Forms.Button();
        btnDelete = new System.Windows.Forms.Button();
        label1 = new System.Windows.Forms.Label();
        statusStrip = new System.Windows.Forms.StatusStrip();
        statusLabel = new System.Windows.Forms.ToolStripStatusLabel();
        saveFileDialog = new System.Windows.Forms.SaveFileDialog();
        openFileDialog = new System.Windows.Forms.OpenFileDialog();
        statusStrip.SuspendLayout();
        SuspendLayout();

        // treeViewLocal
        treeViewLocal.Dock = System.Windows.Forms.DockStyle.Left;
        treeViewLocal.Location = new System.Drawing.Point(0, 51);
        treeViewLocal.Name = "treeViewLocal";
        treeViewLocal.Size = new System.Drawing.Size(200, 372);
        treeViewLocal.TabIndex = 0;

        // listViewRemote
        listViewRemote.Columns.AddRange(new System.Windows.Forms.ColumnHeader[] { colName, colSize, colDate });
        listViewRemote.Dock = System.Windows.Forms.DockStyle.Fill;
        listViewRemote.FullRowSelect = true;
        listViewRemote.GridLines = true;
        listViewRemote.HideSelection = false;
        listViewRemote.Location = new System.Drawing.Point(200, 51);
        listViewRemote.MultiSelect = false;
        listViewRemote.Name = "listViewRemote";
        listViewRemote.Size = new System.Drawing.Size(484, 372);
        listViewRemote.TabIndex = 1;
        listViewRemote.UseCompatibleStateImageBehavior = false;
        listViewRemote.View = System.Windows.Forms.View.Details;
        listViewRemote.DoubleClick += listViewRemote_DoubleClick;

        // colName
        colName.Text = "名称";
        colName.Width = 250;

        // colSize
        colSize.Text = "大小";
        colSize.Width = 100;

        // colDate
        colDate.Text = "日期";
        colDate.Width = 120;

        // txtRemotePath
        txtRemotePath.Location = new System.Drawing.Point(12, 12);
        txtRemotePath.Name = "txtRemotePath";
        txtRemotePath.ReadOnly = true;
        txtRemotePath.Size = new System.Drawing.Size(350, 23);
        txtRemotePath.TabIndex = 2;

        // btnGoUp
        btnGoUp.Location = new System.Drawing.Point(368, 10);
        btnGoUp.Name = "btnGoUp";
        btnGoUp.Size = new System.Drawing.Size(40, 27);
        btnGoUp.TabIndex = 3;
        btnGoUp.Text = "↑";
        btnGoUp.UseVisualStyleBackColor = true;
        btnGoUp.Click += btnGoUp_Click;

        // btnRefresh
        btnRefresh.Location = new System.Drawing.Point(414, 10);
        btnRefresh.Name = "btnRefresh";
        btnRefresh.Size = new System.Drawing.Size(60, 27);
        btnRefresh.TabIndex = 4;
        btnRefresh.Text = "刷新";
        btnRefresh.UseVisualStyleBackColor = true;
        btnRefresh.Click += btnRefresh_Click;

        // btnUpload
        btnUpload.Location = new System.Drawing.Point(480, 10);
        btnUpload.Name = "btnUpload";
        btnUpload.Size = new System.Drawing.Size(60, 27);
        btnUpload.TabIndex = 5;
        btnUpload.Text = "上传";
        btnUpload.UseVisualStyleBackColor = true;
        btnUpload.Click += btnUpload_Click;

        // btnDownload
        btnDownload.Location = new System.Drawing.Point(546, 10);
        btnDownload.Name = "btnDownload";
        btnDownload.Size = new System.Drawing.Size(60, 27);
        btnDownload.TabIndex = 6;
        btnDownload.Text = "下载";
        btnDownload.UseVisualStyleBackColor = true;
        btnDownload.Click += btnDownload_Click;

        // btnDelete
        btnDelete.Location = new System.Drawing.Point(612, 10);
        btnDelete.Name = "btnDelete";
        btnDelete.Size = new System.Drawing.Size(60, 27);
        btnDelete.TabIndex = 7;
        btnDelete.Text = "删除";
        btnDelete.UseVisualStyleBackColor = true;
        btnDelete.Click += btnDelete_Click;

        // label1
        label1.AutoSize = true;
        label1.Location = new System.Drawing.Point(12, 35);
        label1.Name = "label1";
        label1.Size = new System.Drawing.Size(91, 15);
        label1.TabIndex = 8;
        label1.Text = "手机文件系统：";

        // statusStrip
        statusStrip.Items.AddRange(new System.Windows.Forms.ToolStripItem[] { statusLabel });
        statusStrip.Location = new System.Drawing.Point(0, 423);
        statusStrip.Name = "statusStrip";
        statusStrip.Size = new System.Drawing.Size(684, 22);
        statusStrip.TabIndex = 9;

        // statusLabel
        statusLabel.Name = "statusLabel";
        statusLabel.Size = new System.Drawing.Size(32, 17);
        statusLabel.Text = "就绪";

        // openFileDialog
        openFileDialog.Filter = "所有文件|*.*";
        openFileDialog.Title = "选择要上传的文件";

        // FileTransferForm
        AutoScaleDimensions = new System.Drawing.SizeF(7F, 15F);
        AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
        ClientSize = new System.Drawing.Size(684, 445);
        Controls.Add(listViewRemote);
        Controls.Add(treeViewLocal);
        Controls.Add(statusStrip);
        Controls.Add(btnDelete);
        Controls.Add(btnDownload);
        Controls.Add(btnUpload);
        Controls.Add(btnRefresh);
        Controls.Add(btnGoUp);
        Controls.Add(txtRemotePath);
        Controls.Add(label1);
        MainMenuStrip = null;
        Name = "FileTransferForm";
        StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
        Text = "文件传输";
        statusStrip.ResumeLayout(false);
        statusStrip.PerformLayout();
        ResumeLayout(false);
        PerformLayout();
    }
}

public class FileEntry
{
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public bool IsDirectory { get; set; }
    public string Size { get; set; } = string.Empty;
}
