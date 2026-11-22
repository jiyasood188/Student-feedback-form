import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

public class StudentFeedbackSystem {
    private static final String DATABASE_URL = "jdbc:sqlite:student_feedback.db";

    public static void main(String[] args) {
        try {
            Class.forName("org.sqlite.JDBC");
            createDatabase();
            if (args.length == 1 && "--server".equals(args[0])) {
                FeedbackHttpServer.start();
            } else {
                SwingUtilities.invokeLater(() -> new LoginWindow().setVisible(true));
            }
        } catch (ClassNotFoundException | SQLException | IOException exception) {
            exception.printStackTrace();
            JOptionPane.showMessageDialog(
                    null,
                    "Could not start the application:\n" + exception.getMessage(),
                    "Startup error",
                    JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(DATABASE_URL);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void createDatabase() throws SQLException {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS Students (" +
                    "student_id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "password TEXT NOT NULL)");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS Faculty (" +
                    "faculty_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, " +
                    "department TEXT NOT NULL)");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS Feedback (" +
                    "feedback_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "student_id TEXT NOT NULL, " +
                    "faculty_id INTEGER NOT NULL, " +
                    "teaching_rating INTEGER NOT NULL CHECK(teaching_rating BETWEEN 1 AND 5), " +
                    "communication_rating INTEGER NOT NULL CHECK(communication_rating BETWEEN 1 AND 5), " +
                    "punctuality_rating INTEGER NOT NULL CHECK(punctuality_rating BETWEEN 1 AND 5), " +
                    "comments TEXT NOT NULL, " +
                    "submitted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                    "FOREIGN KEY(student_id) REFERENCES Students(student_id), " +
                    "FOREIGN KEY(faculty_id) REFERENCES Faculty(faculty_id))");

            statement.executeUpdate(
                    "INSERT OR IGNORE INTO Students (student_id, name, password) VALUES " +
                    "('STU001', 'Aarav Patel', 'student123'), " +
                    "('STU002', 'Diya Shah', 'student123'), " +
                    "('STU003', 'Rohan Mehta', 'student123')");
            statement.executeUpdate(
                    "INSERT OR IGNORE INTO Faculty (faculty_id, name, department) VALUES " +
                    "(1, 'Anita Sharma', 'Computer Science'), " +
                    "(2, 'Rahul Verma', 'Mathematics'), " +
                    "(3, 'Neha Joshi', 'Physics')");
        }
    }

    private static void showDatabaseError(JFrame parent, SQLException exception) {
        exception.printStackTrace();
        JOptionPane.showMessageDialog(
                parent,
                "The database operation failed:\n" + exception.getMessage(),
                "Database error",
                JOptionPane.ERROR_MESSAGE);
    }

    private static class LoginWindow extends JFrame {
        private final JTextField studentIdField = new JTextField(16);
        private final JPasswordField passwordField = new JPasswordField(16);

        LoginWindow() {
            setTitle("Student Feedback System - Login");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(380, 250);
            setLocationRelativeTo(null);

            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(18, 24, 18, 24));
            GridBagConstraints layout = new GridBagConstraints();
            layout.insets = new Insets(7, 5, 7, 5);
            layout.anchor = GridBagConstraints.WEST;

            layout.gridx = 0;
            layout.gridy = 0;
            panel.add(new JLabel("Student ID:"), layout);
            layout.gridx = 1;
            panel.add(studentIdField, layout);

            layout.gridx = 0;
            layout.gridy = 1;
            panel.add(new JLabel("Password:"), layout);
            layout.gridx = 1;
            panel.add(passwordField, layout);

            JButton loginButton = new JButton("Login");
            loginButton.addActionListener(event -> login());
            layout.gridx = 1;
            layout.gridy = 2;
            panel.add(loginButton, layout);

            JLabel hint = new JLabel("Demo: STU001 / student123");
            layout.gridx = 0;
            layout.gridy = 3;
            layout.gridwidth = 2;
            panel.add(hint, layout);
            add(panel);
        }

        private void login() {
            String studentId = studentIdField.getText().trim();
            String password = new String(passwordField.getPassword());
            if (studentId.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Enter both your student ID and password.");
                return;
            }

            String query = "SELECT student_id, name FROM Students WHERE student_id = ? AND password = ?";
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, studentId);
                statement.setString(2, password);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        new FeedbackWindow(result.getString("student_id"), result.getString("name"))
                                .setVisible(true);
                        dispose();
                    } else {
                        JOptionPane.showMessageDialog(this, "Student ID or password is incorrect.");
                    }
                }
            } catch (SQLException exception) {
                showDatabaseError(this, exception);
            }
        }
    }

    private static class FeedbackWindow extends JFrame {
        private final String studentId;
        private final JComboBox<FacultyChoice> facultyBox = new JComboBox<>();
        private final JSpinner teachingRating = ratingSpinner();
        private final JSpinner communicationRating = ratingSpinner();
        private final JSpinner punctualityRating = ratingSpinner();
        private final JTextArea commentsArea = new JTextArea(4, 28);
        private final DefaultTableModel summaryModel = tableModel(
                "Faculty", "Department", "Feedback count", "Teaching avg.", "Communication avg.",
                "Punctuality avg.", "Overall avg.");
        private final DefaultTableModel reportModel = tableModel(
                "Date", "Student", "Faculty", "Teaching", "Communication", "Punctuality",
                "Overall", "Comments");

        FeedbackWindow(String studentId, String studentName) {
            this.studentId = studentId;
            setTitle("Student Feedback System - " + studentName);
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(950, 620);
            setLocationRelativeTo(null);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Feedback Form", createFeedbackPanel(studentName));
            tabs.addTab("Feedback Summary", new JScrollPane(createTable(summaryModel)));
            tabs.addTab("Feedback Report", new JScrollPane(createTable(reportModel)));
            tabs.addChangeListener(event -> {
                int selectedTab = tabs.getSelectedIndex();
                if (selectedTab == 1) {
                    refreshSummary();
                } else if (selectedTab == 2) {
                    refreshReport();
                }
            });
            add(tabs);

            loadFaculty();
            refreshSummary();
            refreshReport();
        }

        private JPanel createFeedbackPanel(String studentName) {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(20, 25, 20, 25));
            GridBagConstraints layout = new GridBagConstraints();
            layout.insets = new Insets(7, 6, 7, 6);
            layout.anchor = GridBagConstraints.WEST;
            layout.fill = GridBagConstraints.HORIZONTAL;

            addFormRow(panel, layout, 0, "Logged in as:", new JLabel(studentName));
            addFormRow(panel, layout, 1, "Faculty:", facultyBox);
            addFormRow(panel, layout, 2, "Teaching (1-5):", teachingRating);
            addFormRow(panel, layout, 3, "Communication (1-5):", communicationRating);
            addFormRow(panel, layout, 4, "Punctuality (1-5):", punctualityRating);
            commentsArea.setLineWrap(true);
            commentsArea.setWrapStyleWord(true);
            addFormRow(panel, layout, 5, "Comments (optional):", new JScrollPane(commentsArea));

            JButton submitButton = new JButton("Submit feedback");
            submitButton.addActionListener(event -> submitFeedback());
            layout.gridx = 1;
            layout.gridy = 6;
            layout.weightx = 1;
            panel.add(submitButton, layout);

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.add(panel, BorderLayout.NORTH);
            return wrapper;
        }

        private void addFormRow(
                JPanel panel, GridBagConstraints layout, int row, String label, java.awt.Component input) {
            layout.gridx = 0;
            layout.gridy = row;
            layout.weightx = 0;
            panel.add(new JLabel(label), layout);
            layout.gridx = 1;
            layout.weightx = 1;
            panel.add(input, layout);
        }

        private void loadFaculty() {
            facultyBox.removeAllItems();
            String query = "SELECT faculty_id, name, department FROM Faculty ORDER BY name";
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    facultyBox.addItem(new FacultyChoice(
                            result.getInt("faculty_id"),
                            result.getString("name"),
                            result.getString("department")));
                }
            } catch (SQLException exception) {
                showDatabaseError(this, exception);
            }
        }

        private void submitFeedback() {
            FacultyChoice faculty = (FacultyChoice) facultyBox.getSelectedItem();
            if (faculty == null) {
                JOptionPane.showMessageDialog(this, "There are no faculty members to rate.");
                return;
            }

            String comments = commentsArea.getText().trim();
            if (comments.length() > 500) {
                JOptionPane.showMessageDialog(this, "Comments must be 500 characters or fewer.");
                return;
            }

            String insert = "INSERT INTO Feedback " +
                    "(student_id, faculty_id, teaching_rating, communication_rating, punctuality_rating, comments) " +
                    "VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, studentId);
                statement.setInt(2, faculty.id);
                statement.setInt(3, (Integer) teachingRating.getValue());
                statement.setInt(4, (Integer) communicationRating.getValue());
                statement.setInt(5, (Integer) punctualityRating.getValue());
                statement.setString(6, comments);
                statement.executeUpdate();

                JOptionPane.showMessageDialog(this, "Your feedback was submitted.");
                commentsArea.setText("");
                refreshSummary();
                refreshReport();
            } catch (SQLException exception) {
                showDatabaseError(this, exception);
            }
        }

        private void refreshSummary() {
            String query =
                    "SELECT f.name, f.department, COUNT(b.feedback_id) AS feedback_count, " +
                    "ROUND(AVG(b.teaching_rating), 2) AS teaching_average, " +
                    "ROUND(AVG(b.communication_rating), 2) AS communication_average, " +
                    "ROUND(AVG(b.punctuality_rating), 2) AS punctuality_average, " +
                    "ROUND(AVG((b.teaching_rating + b.communication_rating + b.punctuality_rating) / 3.0), 2) " +
                    "AS overall_average " +
                    "FROM Faculty f LEFT JOIN Feedback b ON f.faculty_id = b.faculty_id " +
                    "GROUP BY f.faculty_id, f.name, f.department ORDER BY f.name";
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet result = statement.executeQuery()) {
                summaryModel.setRowCount(0);
                while (result.next()) {
                    summaryModel.addRow(new Object[] {
                            result.getString("name"),
                            result.getString("department"),
                            result.getInt("feedback_count"),
                            averageOrDash(result, "teaching_average"),
                            averageOrDash(result, "communication_average"),
                            averageOrDash(result, "punctuality_average"),
                            averageOrDash(result, "overall_average")
                    });
                }
            } catch (SQLException exception) {
                showDatabaseError(this, exception);
            }
        }

        private void refreshReport() {
            String query =
                    "SELECT b.submitted_at, s.name AS student_name, f.name AS faculty_name, " +
                    "b.teaching_rating, b.communication_rating, b.punctuality_rating, " +
                    "ROUND((b.teaching_rating + b.communication_rating + b.punctuality_rating) / 3.0, 2) " +
                    "AS overall_rating, b.comments " +
                    "FROM Feedback b JOIN Students s ON b.student_id = s.student_id " +
                    "JOIN Faculty f ON b.faculty_id = f.faculty_id " +
                    "ORDER BY b.submitted_at DESC, b.feedback_id DESC";
            try (Connection connection = connect();
                 PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet result = statement.executeQuery()) {
                reportModel.setRowCount(0);
                while (result.next()) {
                    reportModel.addRow(new Object[] {
                            result.getString("submitted_at"),
                            result.getString("student_name"),
                            result.getString("faculty_name"),
                            result.getInt("teaching_rating"),
                            result.getInt("communication_rating"),
                            result.getInt("punctuality_rating"),
                            result.getDouble("overall_rating"),
                            result.getString("comments")
                    });
                }
            } catch (SQLException exception) {
                showDatabaseError(this, exception);
            }
        }
    }

    private static JSpinner ratingSpinner() {
        return new JSpinner(new SpinnerNumberModel(5, 1, 5, 1));
    }

    private static DefaultTableModel tableModel(String... columnNames) {
        return new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JTable createTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        return table;
    }

    private static String averageOrDash(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? "-" : String.valueOf(value);
    }

    private static class FacultyChoice {
        private final int id;
        private final String name;
        private final String department;

        FacultyChoice(int id, String name, String department) {
            this.id = id;
            this.name = name;
            this.department = department;
        }

        @Override
        public String toString() {
            return name + " - " + department;
        }
    }
}
