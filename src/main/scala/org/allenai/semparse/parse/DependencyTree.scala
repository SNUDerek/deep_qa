package org.allenai.semparse.parse

import scala.collection.mutable

case class Token(word: String, posTag: String, lemma: String, index: Int) {
  override def toString() = s"$word ($lemma): $posTag"

  def addPreposition(prep: String): Token = {
    val newWord = word + "_" + prep
    val newLemma = lemma + "_" + prep
    Token(newWord, posTag, newLemma, index)
  }

  def combineWith(other: Token): Token = {
    val newWord = word + "_" + other.word
    val newLemma = lemma + "_" + other.lemma
    Token(newWord, posTag, newLemma, index)
  }
}
case class Dependency(head: String, headIndex: Int, dependent: String, depIndex: Int, label: String)
case class DependencyTree(token: Token, children: Seq[(DependencyTree, String)]) {
  def isNp(): Boolean = token.posTag.startsWith("NN")
  def isVerb(): Boolean = token.posTag.startsWith("VB")
  def isAdj(): Boolean = token.posTag.startsWith("JJ")
  def isDeterminer(): Boolean = token.posTag.contains("DT")

  lazy val childLabels = children.map(_._2).toSet

  // This adds back in tokens for prepositions, which were stripped when using collapsed
  // dependencies.
  lazy val tokensInYield: Seq[Token] = {
    val trees = (children ++ Seq((this, "self"))).sortBy(_._1.token.index)
    trees.flatMap(tree => {
      tree match {
        case t if t._1 == this => Seq(token)
        case (child, label) => {
          if (label.startsWith("prep_")) {
            val prep = label.replace("prep_", "")
            val prepToken = Token(prep, "IN", prep, child.token.index - 1)
            Seq(prepToken) ++ child.tokensInYield
          } else {
            child.tokensInYield
          }
        }
      }
    })
  }

  // The initial underscore is because "yield" is a reserved word in scala.
  lazy val _yield: String = tokensInYield.map(_.word).mkString(" ")

  lazy val lemmaYield: String = tokensInYield.map(_.lemma).mkString(" ")

  lazy val tokens: Seq[Token] = Seq(token) ++ children.flatMap(_._1.tokens)

  lazy val simplifications: Set[DependencyTree] = {
    var toRemove = children.sortBy(simplificationSortingKey)
    val simplified = mutable.ArrayBuffer[DependencyTree](this)
    var currentTree = this
    Set(this) ++ toRemove.map(child => {
      currentTree = transformers.removeTree(currentTree, child._1)
      currentTree
    })
  }

  def getChildWithLabel(label: String): Option[DependencyTree] = {
    val childrenWithLabel = children.filter(_._2 == label)
    if (childrenWithLabel.size == 1) {
      Some(childrenWithLabel.head._1)
    } else {
      None
    }
  }

  // Anything not shown here will get removed first, then the things at the beginning of this list.
  val simplificationOrder = Seq("prep", "amod")
  def simplificationSortingKey(child: (DependencyTree, String)) = {
    val label = if (child._2.startsWith("prep")) "prep" else child._2
    val labelIndex = simplificationOrder.indexOf(label)
    val tokenIndex = child._1.token.index
    // noun modifiers that come after the noun are more likely to be things to remove first.
    (labelIndex, -tokenIndex)
  }

  def print() {
    _print(1, "ROOT")
  }

  private def _print(level: Int, depLabel: String) {
    for (i <- 1 until level) {
      System.out.print("   ")
    }
    println(s"($depLabel) $token")
    for ((child, label) <- children) {
      child._print(level + 1, label)
    }
  }
}
