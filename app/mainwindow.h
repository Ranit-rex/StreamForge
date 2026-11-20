#pragma once
#include <QMainWindow>
class QLabel;
class QListWidget;
class QPushButton;
class MainWindow final : public QMainWindow
{
    Q_OBJECT
public:
    explicit MainWindow(QWidget *parent = nullptr);
private:
    void buildInterface();
    void applyTheme();
    QWidget *createPanel(const QString &title, QWidget *content, QWidget *parent = nullptr);
    QLabel *engineStatusLabel_ = nullptr;
    QLabel *previewStatusLabel_ = nullptr;
    QListWidget *sceneList_ = nullptr;
    QListWidget *sourceList_ = nullptr;
    QPushButton *startStreamButton_ = nullptr;
    QPushButton *recordButton_ = nullptr;
};
