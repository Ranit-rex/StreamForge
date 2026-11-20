#include "mainwindow.h"
#include <QApplication>
#include <QFont>
int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    QApplication::setApplicationName("StreamForge");
    QApplication::setApplicationVersion("0.1.0");
    QApplication::setOrganizationName("StreamForge Project");
    QFont font("Inter");
    if (!font.exactMatch()) font = QFont("Segoe UI");
    font.setHintingPreference(QFont::PreferFullHinting);
    app.setFont(font);
    MainWindow window;
    window.show();
    return app.exec();
}
