// There's not a real test here, but on compilation the
// switch should have the cases arranged in order from 1-30.
class A {
  def f(x: Int) = x match {
    case 6  => "6"
    case 18 => "18"
    case 7  => "7"
    case 2  => "2"
    case 13 => "13"
    case 11 => "11"
    case 26 => "26"
    case 27 => "27"
    case 29 => "29"
    case 25 => "25"
    case 9  => "9"
    case 17 => "17"
    case 16 => "16"
    case 1  => "1"
    case 30 => "30"
    case 15 => "15"
    case 22 => "22"
    case 19 => "19"
    case 23 => "23"
    case 8  => "8"
    case 28 => "28"
    case 5  => "5"
    case 12 => "12"
    case 10 => "10"
    case 21 => "21"
    case 24 => "24"
    case 4  => "4"
    case 14 => "14"
    case 3  => "3"
    case 20 => "20"
  }
}
  