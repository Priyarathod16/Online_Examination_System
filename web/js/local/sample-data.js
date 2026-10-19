// Seed data for the standalone (in-browser) mode: the same sample exams and questions as database/online_exam.sql.
export const SAMPLE = {
 "java": [
  {
   "text": "Which keyword is used to create a subclass in Java?",
   "options": [
    "implements",
    "extends",
    "inherits",
    "super"
   ],
   "correct": "B"
  },
  {
   "text": "What is the default value of an int instance variable?",
   "options": [
    "null",
    "undefined",
    "0",
    "-1"
   ],
   "correct": "C"
  },
  {
   "text": "Which collection class does NOT allow duplicate elements?",
   "options": [
    "ArrayList",
    "LinkedList",
    "HashSet",
    "Vector"
   ],
   "correct": "C"
  },
  {
   "text": "Which method is the entry point of a Java application?",
   "options": [
    "public static void main(String[] args)",
    "public void start()",
    "static void run()",
    "public static int main()"
   ],
   "correct": "A"
  },
  {
   "text": "Which of these is NOT a primitive type in Java?",
   "options": [
    "int",
    "boolean",
    "String",
    "char"
   ],
   "correct": "C"
  },
  {
   "text": "What does JVM stand for?",
   "options": [
    "Java Variable Machine",
    "Java Virtual Machine",
    "Java Verified Method",
    "Joint Virtual Module"
   ],
   "correct": "B"
  },
  {
   "text": "Which keyword prevents a method from being overridden by a subclass?",
   "options": [
    "static",
    "abstract",
    "volatile",
    "final"
   ],
   "correct": "D"
  },
  {
   "text": "Which exception is thrown when an integer is divided by zero?",
   "options": [
    "NullPointerException",
    "ArithmeticException",
    "NumberFormatException",
    "IOException"
   ],
   "correct": "B"
  },
  {
   "text": "What is the time complexity of reading an element by index from an ArrayList?",
   "options": [
    "O(1)",
    "O(n)",
    "O(log n)",
    "O(n log n)"
   ],
   "correct": "A"
  },
  {
   "text": "Which interface should a class implement to define its natural sort order?",
   "options": [
    "Runnable",
    "Cloneable",
    "Comparable",
    "Serializable"
   ],
   "correct": "C"
  },
  {
   "text": "Which statement about String in Java is true?",
   "options": [
    "Strings are mutable",
    "Strings are immutable",
    "Strings are primitive types",
    "Strings cannot be compared"
   ],
   "correct": "B"
  },
  {
   "text": "Which block always executes after try/catch, whether or not an exception occurred?",
   "options": [
    "catch",
    "finally",
    "throw",
    "default"
   ],
   "correct": "B"
  },
  {
   "text": "What is the result of the integer expression 5 / 2 in Java?",
   "options": [
    "2",
    "2.5",
    "3",
    "2.0"
   ],
   "correct": "A"
  },
  {
   "text": "Which access modifier gives the widest visibility?",
   "options": [
    "private",
    "protected",
    "package-private (no modifier)",
    "public"
   ],
   "correct": "D"
  },
  {
   "text": "Which collection stores key-value pairs using a hash table?",
   "options": [
    "TreeSet",
    "HashMap",
    "ArrayDeque",
    "LinkedList"
   ],
   "correct": "B"
  },
  {
   "text": "Which keyword refers to the current object instance?",
   "options": [
    "this",
    "super",
    "self",
    "current"
   ],
   "correct": "A"
  },
  {
   "text": "What does the static keyword on a method mean?",
   "options": [
    "It belongs to the class rather than to an instance",
    "It cannot return a value",
    "It can only be called once",
    "It is automatically synchronized"
   ],
   "correct": "A"
  },
  {
   "text": "Which Swing class represents a top-level application window?",
   "options": [
    "JPanel",
    "JButton",
    "JFrame",
    "JLabel"
   ],
   "correct": "C"
  },
  {
   "text": "Which JDBC interface runs a precompiled, parameterized SQL statement?",
   "options": [
    "Statement",
    "PreparedStatement",
    "ResultSet",
    "DriverManager"
   ],
   "correct": "B"
  },
  {
   "text": "Which option best describes polymorphism?",
   "options": [
    "One interface with many forms of behaviour",
    "Hiding internal data",
    "Creating objects from classes",
    "Combining data and methods"
   ],
   "correct": "A"
  },
  {
   "text": "What is encapsulation?",
   "options": [
    "Inheriting from several classes",
    "Converting one type to another",
    "Binding data with methods and restricting direct access to it",
    "Creating several methods with the same name"
   ],
   "correct": "C"
  },
  {
   "text": "Which loop is guaranteed to run its body at least once?",
   "options": [
    "for",
    "while",
    "for-each",
    "do-while"
   ],
   "correct": "D"
  },
  {
   "text": "Which HashMap method returns the value mapped to a key?",
   "options": [
    "put",
    "get",
    "remove",
    "clear"
   ],
   "correct": "B"
  },
  {
   "text": "Which operator compares two primitive int values for equality?",
   "options": [
    "=",
    "===",
    "equals",
    "=="
   ],
   "correct": "D"
  },
  {
   "text": "What is the time complexity of the Fisher-Yates shuffle on n elements?",
   "options": [
    "O(n)",
    "O(n log n)",
    "O(n^2)",
    "O(log n)"
   ],
   "correct": "A"
  }
 ],
 "sql": [
  {
   "text": "Which SQL statement retrieves rows from a table?",
   "options": [
    "GET",
    "SELECT",
    "FETCH",
    "OPEN"
   ],
   "correct": "B"
  },
  {
   "text": "Which clause filters rows before they are grouped?",
   "options": [
    "HAVING",
    "GROUP BY",
    "WHERE",
    "ORDER BY"
   ],
   "correct": "C"
  },
  {
   "text": "Which constraint uniquely identifies each row in a table?",
   "options": [
    "FOREIGN KEY",
    "PRIMARY KEY",
    "CHECK",
    "DEFAULT"
   ],
   "correct": "B"
  },
  {
   "text": "Which JOIN returns only the rows that have matching values in both tables?",
   "options": [
    "LEFT JOIN",
    "RIGHT JOIN",
    "CROSS JOIN",
    "INNER JOIN"
   ],
   "correct": "D"
  },
  {
   "text": "Which keyword removes duplicate rows from a result set?",
   "options": [
    "UNIQUE",
    "DISTINCT",
    "DIFFERENT",
    "REMOVE"
   ],
   "correct": "B"
  },
  {
   "text": "Which statement changes existing data in a table?",
   "options": [
    "MODIFY",
    "CHANGE",
    "UPDATE",
    "ALTER ROW"
   ],
   "correct": "C"
  },
  {
   "text": "What does a FOREIGN KEY enforce?",
   "options": [
    "Referential integrity between tables",
    "Faster sorting",
    "Column encryption",
    "Automatic numbering"
   ],
   "correct": "A"
  },
  {
   "text": "Which aggregate function returns the number of rows?",
   "options": [
    "SUM",
    "COUNT",
    "TOTAL",
    "LENGTH"
   ],
   "correct": "B"
  },
  {
   "text": "Which clause sorts the result set?",
   "options": [
    "SORT BY",
    "ORDER BY",
    "GROUP BY",
    "ARRANGE"
   ],
   "correct": "B"
  },
  {
   "text": "Which command permanently saves the changes of a transaction?",
   "options": [
    "ROLLBACK",
    "SAVEPOINT",
    "COMMIT",
    "LOCK"
   ],
   "correct": "C"
  },
  {
   "text": "Which statement removes a table together with all its data?",
   "options": [
    "DELETE",
    "TRUNCATE ROW",
    "REMOVE TABLE",
    "DROP TABLE"
   ],
   "correct": "D"
  },
  {
   "text": "Why should applications use PreparedStatement for user input?",
   "options": [
    "It helps prevent SQL injection",
    "It makes tables smaller",
    "It encrypts the database",
    "It removes the need for indexes"
   ],
   "correct": "A"
  }
 ]
};
