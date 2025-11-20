<?php
// quick_test.php - 简单测试后自动删除自身
if (isset($_GET['test'])) {
    echo "PHP运行正常！";
    // 自动删除此文件
    unlink(__FILE__);
    exit;
}
?>
<a href="?test=1">点击测试PHP支持</a>
